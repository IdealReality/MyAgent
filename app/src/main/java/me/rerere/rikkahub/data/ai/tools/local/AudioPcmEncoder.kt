package me.rerere.rikkahub.data.ai.tools.local

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream

/**
 * Decodes an audio file to the PCM shape the platform speech recognizer accepts through
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE`: mono, 16 kHz, signed 16-bit little-endian.
 *
 * Used by `transcribe_audio_file`. This replaces the old Termux/whisper.cpp path — Android's
 * own MediaExtractor + MediaCodec handle OGG/Opus (Telegram voice notes), MP3, M4A/AAC, FLAC
 * and WAV, so no external binary is needed.
 *
 * Conversion chain per buffer: decode → downmix to mono → resample to 16 kHz (linear
 * interpolation). MediaCodec output is consumed strictly synchronously, so this runs on the
 * calling (IO) dispatcher.
 */
internal object AudioPcmEncoder {

    const val TARGET_SAMPLE_RATE = 16_000

    /**
     * Decode [path] and write raw PCM into [outFile]. Returns the duration in seconds, or
     * null when the file has no decodable audio track.
     */
    fun decodeToPcm16kMono(path: String, outFile: File): Double? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val srcRate = format.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            val srcChannels = format.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalSamplesOut = 0L

            FileOutputStream(outFile).use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex) ?: continue
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> {
                            if (outIndex >= 0) {
                                val buffer = codec.getOutputBuffer(outIndex)
                                if (buffer != null && info.size > 0) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val pcm = ShortArray(info.size / 2)
                                    buffer.asShortBuffer().get(pcm)
                                    val mono = downmixToMono(pcm, srcChannels)
                                    val resampled = resample(mono, srcRate, TARGET_SAMPLE_RATE)
                                    out.write(shortArrayToLittleEndianBytes(resampled))
                                    totalSamplesOut += resampled.size
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }
            }

            if (totalSamplesOut == 0L) return null
            return totalSamplesOut.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Average interleaved channels down to mono. */
    private fun downmixToMono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val frames = pcm.size / channels
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += pcm[frame * channels + ch]
            }
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }

    /** Linear-interpolation resampler. Identity when [srcRate] == [dstRate]. */
    private fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLength = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val s0 = input[i0.coerceIn(0, input.size - 1)].toDouble()
            val s1 = input[(i0 + 1).coerceIn(0, input.size - 1)].toDouble()
            out[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }
        return out
    }

    private fun shortArrayToLittleEndianBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
