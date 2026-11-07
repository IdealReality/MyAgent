package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import java.util.Locale

private const val TAG = "SystemASR"

/**
 * Chat voice input backed by whatever [SpeechRecognizer] the device already ships.
 *
 * The other controllers all need an API key and stream raw audio to a vendor. This one is a
 * thin wrapper over the platform recognizer, so it costs nothing, needs no configuration, and
 * works with the recognizer the user already has (Google speech services, an OEM service such
 * as Honor MagicVoice, or an on-device model). Trade-off: the recognizer decides where audio
 * is processed, so privacy and offline behaviour follow that service rather than this app.
 *
 * Recognition is one session per [start]/[stop]: the platform API emits a single final result
 * and stops listening at the first pause, so a long dictation becomes several sessions. The
 * transcript callback receives the accumulated text so callers do not have to merge results.
 */
class SystemASRController(
    private val context: Context,
    private val provider: ASRProviderSetting.System,
) : ASRController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ASRState(isAvailable = isRecognizerAvailable()))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val completed = StringBuilder()
    private var lastPartial = ""

    /** Set once the on-device recognizer proves it cannot serve this device/language. */
    private var onDeviceUnavailable = false

    private fun isRecognizerAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (!isRecognizerAvailable()) {
            setError("No speech recognition service is installed on this device")
            return
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        completed.setLength(0)
        lastPartial = ""
        _state.update { ASRState(status = ASRStatus.Connecting, isAvailable = true) }

        mainHandler.post { startSession() }
    }

    private fun startSession() {
        releaseRecognizer()
        // `useOnDevice` is a preference, not a promise: an on-device recognizer that lacks the
        // requested language pack fails every session with LANGUAGE_PACK_ERROR, while the
        // default (network) recognizer handles that language fine. So the first on-device
        // attempt that reports that error downgrades for the rest of the dictation instead of
        // leaving the user with a microphone that never produces text.
        val useOnDevice = provider.useOnDevice && !onDeviceUnavailable &&
            android.os.Build.VERSION.SDK_INT >= 31
        val tag = if (useOnDevice) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
                .getOrElse {
                    Log.w(TAG, "on-device recognizer unavailable, falling back to default", it)
                    onDeviceUnavailable = true
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = tag

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                provider.language.ifBlank { Locale.getDefault().toLanguageTag() }
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        tag.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // The platform reports dB relative to the recognizer's own calibration; map the
                // usual -2..10 speech range onto the 0..1 bar the UI draws.
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(level)) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _state.update { it.copy(status = ASRStatus.Stopping) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = firstResult(partialResults) ?: return
                if (text == lastPartial) return
                lastPartial = text
                emit(completed.toString() + text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                if (!text.isNullOrBlank()) {
                    if (completed.isNotEmpty()) completed.append(' ')
                    completed.append(text.trim())
                }
                lastPartial = ""
                emit(completed.toString())
                _state.update { it.copy(status = ASRStatus.Listening) }
                // The platform session ends at the first pause; keep dictating until stop().
                mainHandler.postDelayed({ if (onTranscriptChange != null) startSession() }, 250)
            }

            override fun onError(error: Int) {
                // ERROR_LANGUAGE_NOT_SUPPORTED (12) / ERROR_LANGUAGE_UNAVAILABLE (13): the
                // recognizer cannot serve this language at all. For the on-device engine that
                // usually means the language pack was never downloaded, so retry once with the
                // default recognizer instead of reporting a dead microphone.
                if (error == 12 || error == 13) {
                    if (provider.useOnDevice && !onDeviceUnavailable) {
                        Log.w(TAG, "on-device cannot serve this language (code $error), downgrading")
                        onDeviceUnavailable = true
                        mainHandler.postDelayed(
                            { if (onTranscriptChange != null) startSession() },
                            250
                        )
                        return
                    }
                    setError(
                        "The recognizer does not support ${provider.language.ifBlank { "the device language" }}. " +
                            "Download its language pack in system settings, or pick another ASR provider."
                    )
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> setError(
                        "The recognizer needs a network connection and the request failed. " +
                            "Use an offline recognizer or enable on-device recognition."
                    )

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> setError(
                        "Microphone permission is required"
                    )

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> setError("The recognizer is busy")

                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Silence is not an error: keep the session alive so the user can start
                        // speaking again without tapping the microphone twice.
                        _state.update { it.copy(status = ASRStatus.Listening) }
                        mainHandler.postDelayed(
                            { if (onTranscriptChange != null) startSession() },
                            250
                        )
                    }

                    else -> setError("Recognition failed (code $error)")
                }
            }
        })

        runCatching { tag.startListening(intent) }
            .onFailure {
                Log.e(TAG, "startListening failed", it)
                setError("Could not start the recognizer: ${it.message ?: it::class.java.simpleName}")
            }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun emit(text: String) {
        onTranscriptChange?.invoke(text)
        _state.update { it.copy(transcript = text) }
    }

    private fun setError(message: String) {
        Log.w(TAG, message)
        _state.update {
            it.copy(status = ASRStatus.Error, errorMessage = message)
        }
        onTranscriptChange = null
        releaseRecognizer()
    }

    override fun stop() {
        onTranscriptChange = null
        _state.update { it.copy(status = ASRStatus.Idle) }
        mainHandler.post { releaseRecognizer() }
    }

    override fun dispose() {
        onTranscriptChange = null
        mainHandler.post { releaseRecognizer() }
    }

    private fun releaseRecognizer() {
        val rec = recognizer ?: return
        recognizer = null
        runCatching {
            rec.cancel()
            rec.destroy()
        }
    }
}
