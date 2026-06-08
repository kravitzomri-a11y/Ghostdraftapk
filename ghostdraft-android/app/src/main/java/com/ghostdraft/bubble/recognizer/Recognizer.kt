package com.ghostdraft.bubble.recognizer

/**
 * The transcription backend boundary.
 *
 * On macOS, GhostDraft shells out to whisper.cpp. On Android the default
 * implementation ([AndroidSpeechRecognizer]) uses the platform's on-device
 * SpeechRecognizer — no API key, no model download, works offline on devices
 * that have the offline language pack installed.
 *
 * To swap in a whisper.cpp (NDK) or cloud-Whisper backend later, implement
 * this interface and hand a different instance to BubbleService. Nothing in
 * the bubble UI or the text-injection path needs to change.
 */
interface Recognizer {

    /** Begin listening. Callbacks are always delivered on the main thread. */
    fun start(callbacks: Callbacks)

    /**
     * Stop listening and finalize. The backend should deliver one last
     * [Callbacks.onFinalText] (possibly empty) followed by [Callbacks.onDone].
     */
    fun stop()

    /** Throw everything away without delivering a result. */
    fun cancel()

    /** Release any held resources. */
    fun destroy()

    interface Callbacks {
        /** Interim hypothesis, useful for live UI feedback. May fire repeatedly. */
        fun onPartialText(text: String) {}

        /** The final transcription for this utterance. May be empty (no speech). */
        fun onFinalText(text: String)

        /** A human-readable error. The bubble returns to idle after this. */
        fun onError(message: String)

        /** Listening has fully stopped (after a final result or an error). */
        fun onDone() {}
    }
}
