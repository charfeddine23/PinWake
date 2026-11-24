package com.example.ui.alarm

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityAlarmRingingBinding
import com.example.service.PinWakeService
import com.example.util.DistanceFormatter
import com.example.util.PreAlertPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class AlarmRingingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var savedAlarmStreamVolume: Int? = null
    private var vibrator: Vibrator? = null
    private var crescendoJob: Job? = null

    private var alarmId: Long = -1L
    private var currentDistanceMeters: Float = 0f

    private val finishRingingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PinWakeService.ACTION_FINISH_RINGING_UI) {
                stopAudioAndVibration()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenBehavior()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityAlarmRingingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        // Prevent accidental back-button dismissal while half-asleep
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally blocked: user must slide to dismiss or tap Snooze (500m)
            }
        })

        ContextCompat.registerReceiver(
            this,
            finishRingingReceiver,
            IntentFilter(PinWakeService.ACTION_FINISH_RINGING_UI),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        bindAlarmIntent(intent)

        binding.slideToDismiss.onDismissListener = {
            handleDismiss()
        }

        binding.btnSnooze500m.setOnClickListener {
            handleSnooze500m()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopAudioAndVibration()
        bindAlarmIntent(intent)
    }

    private fun bindAlarmIntent(alarmIntent: android.content.Intent) {
        alarmId = alarmIntent.getLongExtra(PinWakeService.EXTRA_ALARM_ID, -1L)
        val title = alarmIntent.getStringExtra(PinWakeService.EXTRA_ALARM_TITLE)
            ?: getString(R.string.app_name)
        val address = alarmIntent.getStringExtra(PinWakeService.EXTRA_ALARM_ADDRESS) ?: ""
        val radius = alarmIntent.getIntExtra(PinWakeService.EXTRA_ALARM_RADIUS, 500)
        val vibrateEnabled = alarmIntent.getBooleanExtra(PinWakeService.EXTRA_ALARM_VIBRATE, true)
        val customRingtoneUri = alarmIntent.getStringExtra(PinWakeService.EXTRA_ALARM_RINGTONE_URI)
        currentDistanceMeters = alarmIntent.getFloatExtra(PinWakeService.EXTRA_DISTANCE_METERS, 0f)

        binding.tvAlarmTitle.text = title
        binding.tvAlarmAddress.text = address
        binding.tvAlarmSubtitle.text = getString(R.string.alarm_ringing_subtitle, radius)
        binding.tvDistanceRemaining.text = DistanceFormatter.formatCompactDistance(currentDistanceMeters)

        startAlarmAudioWithCrescendo(customRingtoneUri)
        if (vibrateEnabled || PreAlertPreferences.isForceSilentOverrideEnabled(this)) {
            startAlarmVibration()
        }
    }

    private fun applySystemBarInsets() {
        val baseBadgeTopMargin = (24f * resources.displayMetrics.density).toInt()
        val baseDockBottomPadding = binding.alarmBottomDock.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.alarmRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val badgeLp = binding.tvRingingBadge.layoutParams as
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val targetTopMargin = baseBadgeTopMargin + bars.top
            if (badgeLp.topMargin != targetTopMargin) {
                badgeLp.topMargin = targetTopMargin
                binding.tvRingingBadge.layoutParams = badgeLp
            }
            val targetBottomPad = baseDockBottomPadding + bars.bottom
            if (binding.alarmBottomDock.paddingBottom != targetBottomPad) {
                binding.alarmBottomDock.updatePadding(bottom = targetBottomPad)
            }
            insets
        }
    }

    private fun configureLockScreenBehavior() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startAlarmAudioWithCrescendo(customRingtoneUriRaw: String?) {
        val parsedCustomUri = customRingtoneUriRaw
            ?.takeIf {
                it.startsWith("content://") || it.startsWith("android.resource://")
            }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

        val candidateUris = listOfNotNull(
            parsedCustomUri,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ).distinct()
        if (candidateUris.isEmpty()) return

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val manager = getSystemService(AudioManager::class.java)
        audioManager = manager

        // Forced alert: boost alarm stream even when device ringer is in Silent / Vibrate / DND
        if (manager != null && PreAlertPreferences.isForceSilentOverrideEnabled(this)) {
            runCatching {
                val maxVol = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val currentVol = manager.getStreamVolume(AudioManager.STREAM_ALARM)
                val targetVol = (maxVol * 0.90f).roundToInt().coerceAtLeast(1)
                if (currentVol < targetVol) {
                    if (savedAlarmStreamVolume == null) {
                        savedAlarmStreamVolume = currentVol
                    }
                    manager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
                }
            }
        }

        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(alarmAttrs)
            .setAcceptsDelayedFocusGain(false)
            .build()
        audioFocusRequest = focusReq
        runCatching { manager?.requestAudioFocus(focusReq) }

        startPlayerWithFallback(candidateUris, index = 0, alarmAttrs = alarmAttrs)
    }

    private fun startPlayerWithFallback(
        uris: List<android.net.Uri>,
        index: Int,
        alarmAttrs: AudioAttributes
    ) {
        if (index >= uris.size || isDestroyed || isFinishing) return
        val started = runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(alarmAttrs)
                setDataSource(applicationContext, uris[index])
                isLooping = true
                setVolume(0.15f, 0.15f)
                setOnErrorListener { failedPlayer, _, _ ->
                    runCatching { failedPlayer.release() }
                    if (mediaPlayer === failedPlayer) {
                        mediaPlayer = null
                    }
                    startPlayerWithFallback(uris, index + 1, alarmAttrs)
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player

            crescendoJob?.cancel()
            crescendoJob = lifecycleScope.launch {
                var step = 1
                val totalSteps = 15
                while (isActive && step <= totalSteps) {
                    delay(1000L)
                    val vol = (0.15f + (0.85f * step / totalSteps)).coerceIn(0.15f, 1.0f)
                    runCatching { player.setVolume(vol, vol) }
                    step++
                }
            }
            true
        }.getOrDefault(false)

        if (!started) {
            startPlayerWithFallback(uris, index + 1, alarmAttrs)
        }
    }

    private fun startAlarmVibration() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = vib

        if (!vib.hasVibrator()) return
        val timings = longArrayOf(0L, 550L, 220L, 750L, 220L, 550L, 600L)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0)
        val effect = VibrationEffect.createWaveform(timings, amplitudes, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vib.vibrate(
                effect,
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
    }

    private fun handleSnooze500m() {
        stopAudioAndVibration()
        if (alarmId > 0L) {
            PinWakeService.snooze500m(this, alarmId, currentDistanceMeters)
        }
        Toast.makeText(this, R.string.alarm_snooze_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handleDismiss() {
        stopAudioAndVibration()
        PinWakeService.dismissAlarm(this, alarmId)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager?.isKeyguardLocked == true) {
            keyguardManager.requestDismissKeyguard(this, null)
        }
        Toast.makeText(this, R.string.alarm_dismissed_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun stopAudioAndVibration() {
        crescendoJob?.cancel()
        crescendoJob = null
        runCatching {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        mediaPlayer = null
        savedAlarmStreamVolume?.let { prevVol ->
            savedAlarmStreamVolume = null
            runCatching { audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0) }
        }
        audioFocusRequest?.let { req ->
            runCatching { audioManager?.abandonAudioFocusRequest(req) }
        }
        audioFocusRequest = null
        runCatching { vibrator?.cancel() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(finishRingingReceiver) }
        stopAudioAndVibration()
        super.onDestroy()
    }
}
