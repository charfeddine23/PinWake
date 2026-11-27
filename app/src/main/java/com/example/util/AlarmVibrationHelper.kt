package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object AlarmVibrationHelper {

    val ARRIVAL_TIMINGS = longArrayOf(0L, 550L, 220L, 750L, 220L, 550L, 600L)
    val ARRIVAL_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255, 0)
    val PRE_ALERT_TIMINGS = longArrayOf(0L, 220L, 120L, 220L)
    val ATTENTION_TIMINGS = longArrayOf(0L, 350L, 150L, 350L, 150L, 450L)

    fun getVibrator(context: Context): Vibrator? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        }.getOrNull()
    }

    fun buildLoopingArrivalEffect(hasAmplitudeControl: Boolean): VibrationEffect {
        return if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(ARRIVAL_TIMINGS, ARRIVAL_AMPLITUDES, 0)
        } else {
            VibrationEffect.createWaveform(ARRIVAL_TIMINGS, 0)
        }
    }

    fun buildOneShotPatternEffect(timings: LongArray): VibrationEffect {
        return VibrationEffect.createWaveform(timings, -1)
    }

    fun startLoopingArrivalVibration(context: Context): Vibrator? {
        val vib = getVibrator(context) ?: return null
        if (!runCatching { vib.hasVibrator() }.getOrDefault(false)) return null
        val effect = buildLoopingArrivalEffect(
            hasAmplitudeControl = runCatching { vib.hasAmplitudeControl() }.getOrDefault(false)
        )
        vibrateWithAlarmUsage(vib, effect)
        return vib
    }

    fun vibratePreAlert(context: Context) {
        val vib = getVibrator(context) ?: return
        if (!runCatching { vib.hasVibrator() }.getOrDefault(false)) return
        val effect = buildOneShotPatternEffect(PRE_ALERT_TIMINGS)
        if (PreAlertPreferences.isForceSilentOverrideEnabled(context)) {
            vibrateWithAlarmUsage(vib, effect)
        } else {
            vibrateWithNotificationUsage(vib, effect)
        }
    }

    fun vibrateAttention(context: Context, timings: LongArray = ATTENTION_TIMINGS) {
        val vib = getVibrator(context) ?: return
        if (!runCatching { vib.hasVibrator() }.getOrDefault(false)) return
        vibrateWithAlarmUsage(vib, buildOneShotPatternEffect(timings))
    }

    fun cancelVibration(context: Context) {
        runCatching { getVibrator(context)?.cancel() }
    }

    fun cancelVibrator(vibrator: Vibrator?) {
        runCatching { vibrator?.cancel() }
    }

    private fun vibrateWithAlarmUsage(vib: Vibrator, effect: VibrationEffect) {
        runCatching {
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
    }

    private fun vibrateWithNotificationUsage(vib: Vibrator, effect: VibrationEffect) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
    }
}
