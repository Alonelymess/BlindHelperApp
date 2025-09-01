package com.google.ar.core.examples.kotlin.helloar

import android.app.Activity
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.ar.core.examples.kotlin.helloar.HelloArRenderer.Companion.TAG
import java.util.Locale
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class VoiceAssistant {
    private var textToSpeech: TextToSpeech? = null

    private var isInitialized = false

    private var vibrator: Vibrator? = null

    interface SpeechCompletionListener {
        fun onSpeechFinished()
    }

    // A map to hold the Deferred objects for each speech request
    private val listenerMap = mutableMapOf<String, SpeechCompletionListener>()

    companion object {
        private const val TAG = "VoiceAssistant"
        const val REQUEST_CODE_SPEECH_INPUT = 100 // Or any unique request code
    }

    fun initializeTextToSpeech(context: Context) {
        // Prevent re-initialization
        if (isInitialized || textToSpeech != null) {
            return
        }

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported by TTS engine.")
                    isInitialized = false
                } else {
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { }

                        override fun onDone(utteranceId: String?) {
                            // When speech is done, find and invoke the listener
                            listenerMap[utteranceId]?.onSpeechFinished()
                            // Clean up the map
                            listenerMap.remove(utteranceId)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e(TAG, "Speech error on utteranceId: $utteranceId")
                            // Also invoke the listener on error to unblock the flow
                            listenerMap[utteranceId]?.onSpeechFinished()
                            listenerMap.remove(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            onError(utteranceId, 0)
                        }
                    })
                    Log.d(TAG, "TextToSpeech engine initialized successfully.")
                    isInitialized = true // Set the flag only on success
                }
            } else {
                Log.e(TAG, "TextToSpeech initialization failed with status: $status")
                isInitialized = false
            }
        }
        // Initialize the Vibrator service
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Triggers a simple vibration pattern.
     */
    fun vibrate() {
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Vibrate for 500 milliseconds with default amplitude.
                vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                // deprecated in API 26
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500)
            }
        }
    }

//    // Initialize TextToSpeech
//    fun initializeTextToSpeechCouroutine(context: Context) {
//        textToSpeech = TextToSpeech(context) { status ->
//            if (status == TextToSpeech.SUCCESS) {
//                isInitialized = true
//                // The listener is now critical for resuming coroutines
//                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
//                    override fun onStart(utteranceId: String?) {
//                        // Optional: Log when speech starts
//                    }
//
//                    override fun onDone(utteranceId: String?) {
//                        // Find the corresponding deferred and complete it
//                        speechCompletionMap[utteranceId]?.complete(Unit)
//                        speechCompletionMap.remove(utteranceId)
//                    }
//
//                    override fun onError(utteranceId: String?, errorCode: Int) {
//                        // On error, also complete and remove the deferred to prevent leaks
//                        speechCompletionMap[utteranceId]?.complete(Unit)
//                        speechCompletionMap.remove(utteranceId)
//                        Log.e(TAG, "Speech error on utteranceId: $utteranceId, code: $errorCode")
//                    }
//
//                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, 0)"))
//                    override fun onError(utteranceId: String?) {
//                        onError(utteranceId, 0)
//                    }
//                })
//            } else {
//                isInitialized = false
//            }
//        }
//    }
//
//    /**
//     * This is a suspending function that speaks text and waits for it to finish.
//     * It will resume when the TTS engine's onDone callback is fired.
//     */
//    suspend fun speakTextAndWait(text: String) {
//        if (!isInitialized) {
//            Log.e(TAG, "SpeakAndWait called but TTS not initialized.")
//            return
//        }
//
//        // Generate a unique ID for this specific speech request
//        val utteranceId = System.currentTimeMillis().toString()
//        val deferred = CompletableDeferred<Unit>()
//        speechCompletionMap[utteranceId] = deferred
//
//        // Speak the text using the unique utteranceId
//        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
//
//        // Wait for the speech to complete, with a timeout for safety
//        withTimeoutOrNull(15000) { // 15-second timeout
//            deferred.await()
//        }
//        Log.d(TAG, "Speech finished, coroutine resumed.")
//    }

    /**
     * Speaks text and invokes the listener's onSpeechFinished() method upon completion.
     */
    fun speakTextAndWait(text: String, listener: SpeechCompletionListener) {
        if (text.isBlank()) {
            // If there's nothing to say, call the listener immediately
            listener.onSpeechFinished()
            return
        }

        if (isInitialized) {
            val utteranceId = System.currentTimeMillis().toString()
            // Store the listener with its unique ID
            listenerMap[utteranceId] = listener
            // Speak with the unique ID
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            Log.e(TAG, "SpeakAndWait called but TTS not initialized.")
            // If TTS isn't ready, call the listener immediately to prevent getting stuck
            listener.onSpeechFinished()
        }
    }



    suspend fun speakTextAndAWait(text: String) {
        // This function will suspend the coroutine until the callback is invoked.
        return suspendCancellableCoroutine { continuation ->
            speakTextAndWait(text, object : SpeechCompletionListener {
                override fun onSpeechFinished() {
                    // Resume the coroutine when speech is finished.
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            })
        }
    }

    /**
     * Creates an Intent to start the voice recognition activity.
     * The calling Activity/Fragment is responsible for launching this intent
     * using registerForActivityResult and handling the result.
     */
    fun createVoiceInputIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something...") // Optional prompt
        return intent
    }

    /**
     * Parses the voice input result from the Activity's onActivityResult.
     * Call this from your Activity's ActivityResultCallback.
     */
    fun parseVoiceInputResult(resultCode: Int, data: Intent?): String? {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            return result?.get(0) // Get the first and most confident result
        }
        return null
    }


    // Speak the text
    fun speakText(text: String) {
        if (isInitialized) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
        } else {
            Log.e(TAG, "TextToSpeech is not yet initialized.")
        }
    }

    // Release TextToSpeech resources
    fun shutdownTextToSpeech() {
        isInitialized = false
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        vibrator = null
    }
}