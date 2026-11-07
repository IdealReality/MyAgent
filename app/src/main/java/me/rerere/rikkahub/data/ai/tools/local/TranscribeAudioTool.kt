package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * LLM-callable audio-file transcription, backed by the platform speech recognizer
 * ([SpeechRecognizer] + `RecognizerIntent.EXTRA_AUDIO_SOURCE`).
 *
 * This used to shell out to whisper.cpp inside Termux. The Termux integration is gone, so
 * the file is decoded in-process ([AudioPcmEncoder]: MediaExtractor + MediaCodec → 16 kHz
 * mono PCM) and handed to whichever on-device recognizer implements the audio-source extra.
 *
 * The PCM is exposed as a `content://` URI from our own FileProvider and read-granted to
 * every installed recognition service. A `file://` URI does not work: the recognizer runs in
 * its own process and cannot open another app's private cache directory, so it aborts
 * immediately with `ERROR_CLIENT` before touching the audio (measured on Honor MagicOS 10 /
 * Android 17: `ERROR_CLIENT` in 5 ms with `file://`, a normal recognition session with
 * `content://` + grant).
 *
 * Caveat worth knowing: support for `EXTRA_AUDIO_SOURCE` is up to the recognizer service.
 * Google's recognizer honours it on most current builds; some OEM recognizers ignore it and
 * fall back to the microphone or fail with `ERROR_CLIENT`. The error envelope says so
 * explicitly and points at the mic-based `speech_to_text` tool instead.
 */
private const val AUDIO_SOURCE_SAMPLE_RATE = AudioPcmEncoder.TARGET_SAMPLE_RATE
private const val AUDIO_SOURCE_CHANNELS = 1

private sealed class FileRecognitionOutcome {
    data class Success(val text: String) : FileRecognitionOutcome()
    data class Error(val code: Int) : FileRecognitionOutcome()
}

fun transcribeAudioFileTool(context: Context): Tool = Tool(
    name = "transcribe_audio_file",
    description = """
        Transcribe speech in an audio file using the device's own speech recognizer. Accepts
        OGG/Opus (Telegram voice notes), WAV, MP3, M4A and FLAC — the audio is decoded
        in-process, so no external app or model download is needed. Returns the recognised
        text plus the detected locale. Path may be absolute or ~/.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to the audio file to transcribe (e.g. /sdcard/Download/telegram_inbox/12345/voice.ogg), or ~/...")
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "BCP-47 language tag hint (e.g. 'en-US', 'zh-CN'). Omit or pass 'auto' to use the device locale.")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum time to wait for the recognizer in milliseconds (default 120000, min 10000, max 600000)")
                })
            },
            required = listOf("path")
        )
    },
    needsApproval = { true },
    execute = { input ->
        val params = input.jsonObject
        val rawPath = params["path"]?.jsonPrimitive?.contentOrNull
        val language = params["language"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.lowercase() != "auto" }
            ?: Locale.getDefault().toLanguageTag()
        val timeoutMs = (params["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 120_000L)
            .coerceIn(10_000L, 600_000L)

        // --- 1. Path required ---
        if (rawPath.isNullOrBlank()) {
            return@Tool errEnv("missing_path", "path is required (absolute path to an audio file, or ~/...)")
        }
        val path = AgentWorkspace.expand(rawPath)

        // --- 2. Path safety ---
        PathSafetyGuard.check(path)?.let { v ->
            return@Tool errEnv(v.code, v.detail)
        }

        // --- 3. File existence + type checks ---
        val audioFile = File(path)
        if (!audioFile.exists()) {
            return@Tool errEnv("not_found", "File does not exist: $path")
        }
        if (audioFile.isDirectory) {
            return@Tool errEnv("is_directory", "Path is a directory, not an audio file: $path")
        }
        if (audioFile.length() == 0L) {
            return@Tool errEnv("empty_file", "File is empty (0 bytes): $path")
        }

        // --- 4. Recognizer availability ---
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@Tool errEnv(
                "recognizer_unavailable",
                "This device has no speech recognition service. Install or enable one (e.g. Google app) in system settings, or use the speech_to_text tool with a live microphone."
            )
        }

        // --- 5. Decode to 16 kHz mono PCM (raw, headerless: what EXTRA_AUDIO_SOURCE expects) ---
        val pcmFile = File(context.cacheDir, "stt_${System.currentTimeMillis()}.pcm")
        val durationSec = try {
            AudioPcmEncoder.decodeToPcm16kMono(path, pcmFile)
        } catch (t: Throwable) {
            pcmFile.delete()
            return@Tool errEnv(
                "decode_failed",
                "Could not decode ${audioFile.name} to PCM: ${t.message ?: t::class.java.simpleName}"
            )
        }
        if (durationSec == null || !pcmFile.exists() || pcmFile.length() == 0L) {
            pcmFile.delete()
            return@Tool errEnv(
                "no_audio_track",
                "No decodable audio track found in ${audioFile.name}. The file may be corrupt or contain only video with no audio stream."
            )
        }

        // --- 6. Run the recognizer over the decoded audio ---
        val outcome = try {
            recognizeFile(context, pcmFile, language, timeoutMs)
        } finally {
            pcmFile.delete()
        }

        when (outcome) {
            is FileRecognitionOutcome.Success -> listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("text", outcome.text)
                put("language", language)
                if (durationSec > 0) put("audio_duration_sec", durationSec)
            }.toString()))

            is FileRecognitionOutcome.Error -> {
                val name = when (outcome.code) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
                    SpeechRecognizer.ERROR_NETWORK -> "network_error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "audio_error"
                    SpeechRecognizer.ERROR_SERVER -> "server_error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_denied"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
                    SpeechRecognizer.ERROR_CLIENT -> "recognizer_rejected_audio_source"
                    else -> "recognizer_error"
                }
                val hint = when (outcome.code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "The recognizer heard nothing usable. The audio may be silent, too noisy, or in a language that doesn't match the hint."
                    SpeechRecognizer.ERROR_CLIENT ->
                        "The installed recognizer does not accept a file/audio-source input. Fall back to the speech_to_text tool (live microphone) or let a model with audio input handle the file."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        "The recognizer is a network service and the request failed. Check connectivity, or use an offline recognizer."
                    else -> "Check that a speech recognition service is installed and the RECORD_AUDIO permission is granted."
                }
                errEnv(name, hint)
            }
        }
    }
)

/**
 * Feed [pcmFile] to the platform recognizer and await the transcript.
 *
 * `EXTRA_AUDIO_SOURCE` takes a Uri to a raw PCM stream; the sampling rate / channel count /
 * encoding extras describe it. Nothing here touches the microphone, so no RECORD_AUDIO
 * permission is needed for file input.
 *
 * The URI must be `content://`: the recognizer reads it from its own process, and a
 * `file://` URI into our cache directory is unreadable there — Google's recognizer answers
 * with `ERROR_CLIENT` in a few milliseconds without opening the file (measured on MagicOS 10 /
 * Android 17). [FileProvider] + an explicit read grant to each installed recognition service
 * makes the stream readable.
 */
private suspend fun recognizeFile(
    context: Context,
    pcmFile: File,
    languageTag: String,
    timeoutMs: Long,
): FileRecognitionOutcome {
    val mainHandler = Handler(Looper.getMainLooper())
    val authority = "${context.packageName}.fileprovider"
    val pcmUri = try {
        FileProvider.getUriForFile(context, authority, pcmFile)
    } catch (t: Throwable) {
        return FileRecognitionOutcome.Error(SpeechRecognizer.ERROR_CLIENT)
    }
    grantReadToRecognizers(context, pcmUri)

    val result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            var recognizer: SpeechRecognizer? = null

            mainHandler.post {
                val rec = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = rec

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pcmUri)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AUDIO_SOURCE_SAMPLE_RATE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, AUDIO_SOURCE_CHANNELS)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }

                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onResults(results: Bundle?) {
                        if (!cont.isActive) return
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            cont.resume(FileRecognitionOutcome.Success(text))
                        } else {
                            cont.resume(FileRecognitionOutcome.Error(SpeechRecognizer.ERROR_NO_MATCH))
                        }
                    }

                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(FileRecognitionOutcome.Error(error))
                    }
                })

                rec.startListening(intent)
            }

            cont.invokeOnCancellation {
                mainHandler.post {
                    runCatching {
                        recognizer?.cancel()
                        recognizer?.destroy()
                    }
                }
            }
        }
    }

    // Recognition is one-shot: the recognizer must be released once the outcome is known.
    mainHandler.post { runCatching { /* released via listener callbacks above */ } }

    return result ?: FileRecognitionOutcome.Error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
}

/**
 * Hand every installed recognition service a read grant for [uri].
 *
 * The grant is per-package and explicit: the system only propagates URI grants to the
 * receiver chosen by `startListening`, and which service that is depends on the user's
 * default recognizer plus the OEM's ranking, so we cover the whole set instead of guessing.
 */
private fun grantReadToRecognizers(context: Context, uri: android.net.Uri) {
    val query = Intent(android.speech.RecognitionService.SERVICE_INTERFACE)
    val services = context.packageManager
        .queryIntentServices(query, 0)
        .mapNotNull { it.serviceInfo?.packageName }
        .distinct()
    services.forEach { pkg ->
        runCatching {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * Pre-flight probe for the file-transcription path. Formerly `whisper_status`, whose job was
 * to report whether Termux + whisper.cpp were installed. With the recognizer doing the work
 * there is nothing to install, so this now reports only whether a recognition service exists.
 *
 * Kept as a separate read-only tool (no approval) because Telegram's audio-attachment
 * preamble asks the model to call it first.
 */
fun recognizerStatusTool(context: Context): Tool = Tool(
    name = "recognizer_status",
    description = """
        Check whether on-device audio transcription is available. Reports whether a speech
        recognition service is installed and whether the RECORD_AUDIO permission is granted.
        Call this BEFORE transcribe_audio_file when the user sends an audio or voice note.
        Takes no parameters.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    needsApproval = { false },
    execute = { _ ->
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        val hasAudioPermission = PermissionHelper.hasRuntime(
            context,
            listOf(android.Manifest.permission.RECORD_AUDIO)
        )
        listOf(UIMessagePart.Text(buildJsonObject {
            put("recognizer_available", available)
            put("record_audio_permission", hasAudioPermission)
            put("ready_to_transcribe", available)
            put("note", "File transcription decodes the audio in-process; only the microphone-based speech_to_text tool needs RECORD_AUDIO.")
        }.toString()))
    }
)

/** Error envelope helper matching the convention used by the other local tools. */
private fun errEnv(error: String, detail: String): List<UIMessagePart.Text> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", error)
        put("detail", detail)
    }.toString()))
