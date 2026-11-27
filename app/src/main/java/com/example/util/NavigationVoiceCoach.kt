package com.example.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class NavigationVoiceCoach(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isReady = false
    var isMuted: Boolean = false
        set(value) {
            field = value
            if (value) {
                runCatching { tts?.stop() }
            }
        }

    private var lastStepIndex: Int = -1
    private var lastDistanceBand: Int = -1
    private var announcedArrival = false

    init {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    applyLocale()
                    isReady = true
                }
            }
        }
    }

    private fun applyLocale() {
        val lang = LanguageManager.getCurrentLanguageCode(appContext)
        val locale = if (lang == "ar") Locale("ar") else Locale.US
        runCatching {
            val res = tts?.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
        }
    }

    fun maybeAnnounceProgress(
        step: NavStep,
        distanceToTurnMeters: Float,
        hasArrived: Boolean,
        spokenInstruction: String
    ) {
        if (isMuted || !isReady || spokenInstruction.isBlank()) return

        if (hasArrived) {
            if (!announcedArrival) {
                announcedArrival = true
                speak(spokenInstruction)
            }
            return
        }

        val band = when {
            distanceToTurnMeters <= 35f -> 3
            distanceToTurnMeters <= 120f -> 2
            distanceToTurnMeters <= 320f -> 1
            else -> 0
        }

        if (step.index != lastStepIndex) {
            lastStepIndex = step.index
            lastDistanceBand = band
            if (band > 0 || distanceToTurnMeters > 320f) {
                speak(spokenInstruction)
            }
            return
        }

        if (band > lastDistanceBand && band >= 2) {
            lastDistanceBand = band
            speak(spokenInstruction)
        }
    }

    fun resetForNewRoute() {
        lastStepIndex = -1
        lastDistanceBand = -1
        announcedArrival = false
    }

    private fun speak(text: String) {
        applyLocale()
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pinwake_nav_${System.currentTimeMillis()}")
        }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isReady = false
    }
}
