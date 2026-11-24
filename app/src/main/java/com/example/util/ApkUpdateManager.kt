package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.remote.GitHubReleaseApiService
import com.example.data.remote.NominatimApiService
import com.example.data.remote.dto.GitHubAssetDto
import com.example.data.remote.dto.GitHubReleaseDto
import com.example.ui.dashboard.UpdateCheckEvent
import com.example.ui.dashboard.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkAsset: GitHubAssetDto
)

sealed class UpdateDownloadState {
    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int
    ) : UpdateDownloadState()

    data class Completed(val apkFile: File) : UpdateDownloadState()
    data class Failed(val message: String) : UpdateDownloadState()
}

class ApkUpdateManager(
    private val context: Context,
    private val gitHubApi: GitHubReleaseApiService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _updateEvent = MutableStateFlow<UpdateCheckEvent?>(null)
    val updateEvent: StateFlow<UpdateCheckEvent?> = _updateEvent.asStateFlow()

    private var hasCompletedAutoCheck = false
    private var downloadJob: Job? = null

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun ensureCheckedAndAutoDownload(manual: Boolean = false) {
        if (!manual && hasCompletedAutoCheck) return
        val current = _updateState.value
        if (current.isChecking || current.isDownloading) return

        scope.launch {
            _updateState.value = _updateState.value.copy(isChecking = true, errorMessage = null)
            val result = checkForUpdate()
            result.onSuccess { info ->
                hasCompletedAutoCheck = true
                if (info == null) {
                    _updateState.value = UpdateUiState(isChecking = false)
                    if (manual) {
                        _updateEvent.value = UpdateCheckEvent.UpToDate
                    }
                    return@onSuccess
                }

                val cachedApk = findExistingDownloadedApk(info)
                if (cachedApk != null) {
                    _updateState.value = UpdateUiState(
                        isChecking = false,
                        availableUpdate = info,
                        isDownloading = false,
                        downloadPercent = 100,
                        downloadedApkFile = cachedApk
                    )
                } else {
                    _updateState.value = UpdateUiState(
                        isChecking = false,
                        availableUpdate = info
                    )
                    // Automatically download in background without interrupting the user
                    startInAppDownload(onReadyToInstall = null)
                }

                if (manual) {
                    _updateEvent.value = UpdateCheckEvent.UpdateFound(info.versionName)
                }
            }.onFailure {
                _updateState.value = _updateState.value.copy(isChecking = false)
                if (manual) {
                    _updateEvent.value = UpdateCheckEvent.Failed
                }
            }
        }
    }

    fun consumeUpdateEvent() {
        _updateEvent.value = null
    }

    fun startInAppDownload(onReadyToInstall: ((File) -> Unit)? = null) {
        val current = _updateState.value
        val readyFile = current.downloadedApkFile
        if (readyFile != null && readyFile.exists()) {
            onReadyToInstall?.invoke(readyFile)
            return
        }
        val info = current.availableUpdate ?: return
        val cachedApk = findExistingDownloadedApk(info)
        if (cachedApk != null) {
            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                downloadPercent = 100,
                downloadedApkFile = cachedApk,
                errorMessage = null
            )
            onReadyToInstall?.invoke(cachedApk)
            return
        }
        if (current.isDownloading) return

        downloadJob?.cancel()
        downloadJob = scope.launch {
            _updateState.value = _updateState.value.copy(
                isDownloading = true,
                downloadPercent = 0,
                downloadedMb = 0.0,
                totalMb = info.apkAsset.size / (1024.0 * 1024.0),
                errorMessage = null
            )
            downloadApk(info).collect { state ->
                when (state) {
                    is UpdateDownloadState.Progress -> {
                        _updateState.value = _updateState.value.copy(
                            isDownloading = true,
                            downloadPercent = state.percent,
                            downloadedMb = state.downloadedBytes / (1024.0 * 1024.0),
                            totalMb = (if (state.totalBytes > 0) state.totalBytes else info.apkAsset.size) /
                                (1024.0 * 1024.0)
                        )
                    }
                    is UpdateDownloadState.Completed -> {
                        _updateState.value = _updateState.value.copy(
                            isDownloading = false,
                            downloadPercent = 100,
                            downloadedApkFile = state.apkFile,
                            errorMessage = null
                        )
                        onReadyToInstall?.invoke(state.apkFile)
                    }
                    is UpdateDownloadState.Failed -> {
                        _updateState.value = _updateState.value.copy(
                            isDownloading = false,
                            errorMessage = state.message
                        )
                    }
                }
            }
        }
    }

    fun findExistingDownloadedApk(updateInfo: AppUpdateInfo): File? {
        val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME)
        if (!updatesDir.exists()) return null
        val targetFile = File(updatesDir, versionedApkName(updateInfo))
        if (!targetFile.exists() || targetFile.length() <= 0L) return null
        if (updateInfo.apkAsset.size > 0L && targetFile.length() != updateInfo.apkAsset.size) {
            return null
        }
        return targetFile
    }

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val release: GitHubReleaseDto = gitHubApi.getLatestRelease()
            if (release.draft || release.prerelease) return@runCatching null

            val asset = release.firstApkAsset() ?: return@runCatching null
            if (!isTrustedGitHubHttpsUrl(asset.browserDownloadUrl)) {
                return@runCatching null
            }

            val remoteVersion = normalizeVersion(release.tagName)
            val localVersion = normalizeVersion(BuildConfig.VERSION_NAME)
            val safeReleaseUrl = release.htmlUrl
                .takeIf { isTrustedGitHubHttpsUrl(it) }
                ?: GitHubReleaseApiService.REPO_RELEASES_URL

            if (isNewerVersion(remoteVersion, localVersion)) {
                AppUpdateInfo(
                    versionName = remoteVersion,
                    tagName = release.tagName,
                    releaseNotes = release.body.trim(),
                    releasePageUrl = safeReleaseUrl,
                    apkAsset = asset
                )
            } else {
                null
            }
        }
    }

    fun downloadApk(updateInfo: AppUpdateInfo): Flow<UpdateDownloadState> = flow {
        if (!isTrustedGitHubHttpsUrl(updateInfo.apkAsset.browserDownloadUrl)) {
            emit(UpdateDownloadState.Failed("Untrusted update download URL"))
            return@flow
        }

        val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
        val finalName = versionedApkName(updateInfo)
        val targetFile = File(updatesDir, finalName)
        val tempFile = File(updatesDir, "$finalName.tmp")

        // Remove stale APKs and leftover temp files
        updatesDir.listFiles()?.forEach { file ->
            if (file.name != finalName && (file.name.endsWith(".apk", true) || file.name.endsWith(".tmp", true))) {
                file.delete()
            }
        }
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val request = Request.Builder()
            .url(updateInfo.apkAsset.browserDownloadUrl)
            .header("User-Agent", NominatimApiService.USER_AGENT)
            .build()

        try {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(UpdateDownloadState.Failed("HTTP ${response.code}"))
                    return@flow
                }
                val body = response.body ?: run {
                    emit(UpdateDownloadState.Failed("Empty response body"))
                    return@flow
                }

                val contentLength = body.contentLength().let {
                    if (it > 0L) it else updateInfo.apkAsset.size
                }

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var totalRead = 0L
                        var lastEmittedPercent = -1

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            totalRead += read

                            val percent = if (contentLength > 0L) {
                                ((totalRead * 100L) / contentLength).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastEmittedPercent) {
                                lastEmittedPercent = percent
                                emit(UpdateDownloadState.Progress(totalRead, contentLength, percent))
                            }
                        }
                        output.flush()
                    }
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    emit(UpdateDownloadState.Failed("Downloaded APK is empty"))
                    return@flow
                }

                if (updateInfo.apkAsset.size > 0L && tempFile.length() != updateInfo.apkAsset.size) {
                    tempFile.delete()
                    emit(UpdateDownloadState.Failed("Downloaded APK size mismatch"))
                    return@flow
                }

                if (!verifyOptionalDigest(tempFile, updateInfo.apkAsset.digest)) {
                    tempFile.delete()
                    emit(UpdateDownloadState.Failed("APK SHA-256 checksum verification failed"))
                    return@flow
                }

                if (!verifyApkSignatureMatchesCurrentApp(tempFile)) {
                    tempFile.delete()
                    emit(UpdateDownloadState.Failed("APK signing certificate mismatch"))
                    return@flow
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val finalized = if (tempFile.renameTo(targetFile)) {
                    targetFile
                } else {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    targetFile
                }

                emit(UpdateDownloadState.Completed(finalized))
            }
        } catch (t: Throwable) {
            tempFile.delete()
            emit(UpdateDownloadState.Failed(t.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    fun verifyApkSignatureMatchesCurrentApp(apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0L) return false
        return runCatching {
            val pm = context.packageManager
            val installedSigners = getPackageSignatureDigests(pm, context.packageName, isArchive = false)
            if (installedSigners.isEmpty()) return@runCatching false

            val archiveSigners = getPackageSignatureDigests(pm, apkFile.absolutePath, isArchive = true)
            archiveSigners.isNotEmpty() && archiveSigners.any { it in installedSigners }
        }.getOrDefault(false)
    }

    private fun verifyOptionalDigest(file: File, expectedDigest: String?): Boolean {
        val normalizedExpected = expectedDigest
            ?.trim()
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { it.length == 64 }
            ?: return true
        val actual = computeFileSha256(file) ?: return false
        return actual.equals(normalizedExpected, ignoreCase = true)
    }

    private fun computeFileSha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun getPackageSignatureDigests(
        pm: PackageManager,
        target: String,
        isArchive: Boolean
    ): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo? = if (isArchive) {
            pm.getPackageArchiveInfo(target, flags)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(target, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(target, flags)
            }
        }
        if (info == null) return emptySet()

        val rawSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()

        val sha256 = MessageDigest.getInstance("SHA-256")
        return rawSignatures.map { sig ->
            sha256.reset()
            sha256.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildInstallPermissionIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun buildInstallApkIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun versionedApkName(updateInfo: AppUpdateInfo): String {
        val cleanVersion = normalizeVersion(updateInfo.versionName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "update" }
        return "PinWake-v$cleanVersion.apk"
    }

    companion object {
        private const val UPDATES_DIR_NAME = "updates"
        private val TRUSTED_GITHUB_HOSTS = setOf(
            "github.com",
            "api.github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        fun isTrustedGitHubHttpsUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val parsed = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
            if (!parsed.scheme.equals("https", ignoreCase = true)) return false
            val host = parsed.host?.lowercase() ?: return false
            return host in TRUSTED_GITHUB_HOSTS || host.endsWith(".githubusercontent.com")
        }

        fun normalizeVersion(raw: String): String {
            return raw.trim().removePrefix("v").removePrefix("V")
        }

        fun isNewerVersion(remote: String, local: String): Boolean {
            val remoteParts = parseSemVer(remote)
            val localParts = parseSemVer(local)
            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r != l) return r > l
            }
            return false
        }

        private fun parseSemVer(version: String): List<Int> {
            return normalizeVersion(version)
                .substringBefore("-")
                .split(".")
                .map { it.toIntOrNull() ?: 0 }
        }
    }
}
