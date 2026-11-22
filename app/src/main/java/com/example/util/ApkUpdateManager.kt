package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.remote.GitHubReleaseApiService
import com.example.data.remote.NominatimApiService
import com.example.data.remote.dto.GitHubAssetDto
import com.example.data.remote.dto.GitHubReleaseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val release: GitHubReleaseDto = gitHubApi.getLatestRelease()
            if (release.draft) return@runCatching null

            val asset = release.firstApkAsset() ?: return@runCatching null
            val remoteVersion = normalizeVersion(release.tagName)
            val localVersion = normalizeVersion(BuildConfig.VERSION_NAME)

            if (isNewerVersion(remoteVersion, localVersion)) {
                AppUpdateInfo(
                    versionName = remoteVersion,
                    tagName = release.tagName,
                    releaseNotes = release.body.trim(),
                    releasePageUrl = release.htmlUrl.ifBlank { GitHubReleaseApiService.REPO_RELEASES_URL },
                    apkAsset = asset
                )
            } else {
                null
            }
        }
    }

    fun downloadApk(updateInfo: AppUpdateInfo): Flow<UpdateDownloadState> = flow {
        val updatesDir = File(context.cacheDir, "updates").apply {
            if (!exists()) mkdirs()
        }
        // Remove stale APKs before downloading new update
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk", ignoreCase = true)) {
                file.delete()
            }
        }

        val safeName = updateInfo.apkAsset.name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "PinWake-update.apk" }
        val targetFile = File(updatesDir, safeName)

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
                    FileOutputStream(targetFile).use { output ->
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

                if (!targetFile.exists() || targetFile.length() == 0L) {
                    emit(UpdateDownloadState.Failed("Downloaded APK is empty"))
                    return@flow
                }

                emit(UpdateDownloadState.Completed(targetFile))
            }
        } catch (t: Throwable) {
            targetFile.delete()
            emit(UpdateDownloadState.Failed(t.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

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

    companion object {
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
