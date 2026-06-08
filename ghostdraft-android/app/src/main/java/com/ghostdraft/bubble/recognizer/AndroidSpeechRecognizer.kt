package com.ghostdraft.bubble.recognizer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Default backend: Android's [SpeechRecognizer].
 *
 * Prefers on-device recognition (privacy parity with the Mac app's local
 * whisper.cpp). If the device has no offline pack and returns a network
 * error, it automatically retries once with online recognition so dictation
 * still works out of the box.
 *
 * SpeechRecognizer must be created and driven from the main thread; every
 * public method marshals onto the main looper so callers don't have to care.
 */
class AndroidSpeechRecognizer(
    private val context: Context,
    private val languageTag: String? = null, // null = system default
) : Recognizer {

    private val main = Handler(Looper.getMainLooper())
    private var speech: SpeechRecognizer? = null
    private var callbacks: Recognizer.Callbacks? = null
    private var finished = false
    private var triedOnline = false

    override fun start(callbacks: Recognizer.Callbacks) = onMain {
        this.callbacks = callbacks
        finished = false
        triedOnline = false

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            emitError("No speech recognition service is available on this device.")
            return@onMain
        }
        listen(preferOffline = true)
    }

    private fun listen(preferOffline: Boolean) {
        // Recreate each attempt — reusing across sessions causes ERROR_CLIENT
        // on some OEM builds.
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            it.startListening(buildIntent(preferOffline))
        }
    }

    override fun stop() = onMain { speech?.stopListening() }

    override fun cancel() = onMain {
        finished = true
        speech?.cancel()
        emitDone()
    }

    override fun destroy() = onMain {
        speech?.destroy()
        speech = null
        callbacks = null
    }

    private fun buildIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { callbacks?.onPartialText(it) }
        }

        override fun onResults(results: Bundle?) {
            emitFinal(firstResult(results).orEmpty())
        }

        override fun onError(error: Int) {
            when (error) {
                // "Nothing said" — treat as an empty result, not an error popup.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> emitFinal("")

                // No offline pack on this device: retry once online.
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    if (!triedOnline) {
                        triedOnline = true
                        Log.i(TAG, "Offline recognition unavailable; retrying online.")
                        listen(preferOffline = false)
                    } else {
                        emitError(errorText(error))
                    }
                }

                else -> emitError(errorText(error))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun emitFinal(text: String) {
        if (finished) return
        finished = true
        callbacks?.onFinalText(text)
        emitDone()
    }

    private fun emitError(msg: String) {
        if (finished) return
        finished = true
        Log.w(TAG, "recognizer error: $msg")
        callbacks?.onError(msg)
        emitDone()
    }

    private fun emitDone() {
        callbacks?.onDone()
        speech?.destroy()
        speech = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
        SpeechRecognizer.ERROR_NETWORK -> "Network error (and no offline model)."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy — try again."
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
        else -> "Recognition failed (code $code)."
    }

    companion object {
        private const val TAG = "GhostDraftRecognizer"
    }
}
