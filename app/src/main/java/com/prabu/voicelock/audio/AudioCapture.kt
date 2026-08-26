package com.prabu.voicelock.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Microphone capture in the exact format the model expects.
 *
 * 16 kHz, mono, 16-bit PCM, converted to float32 in [-1, 1]. Every one of those
 * has to match what the ONNX frontend was exported with; a sample-rate or
 * normalization mismatch degrades accuracy silently rather than failing, so none
 * of it is configurable here.
 *
 * Audio is never written to disk. The buffer exists only until an embedding has
 * been computed from it.
 */
@Singleton
class AudioCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Result {
        /** @param samples float32 mono at [SAMPLE_RATE], normalized to [-1, 1]. */
        data class Captured(val samples: FloatArray, val rms: Float) : Result

        data object PermissionDenied : Result
        data class TooShort(val seconds: Float) : Result
        data class Failed(val reason: String) : Result
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Records for [seconds], then returns the waveform.
     *
     * @param seconds wall-clock duration to capture. Must leave at least
     *   [MIN_SECONDS] of audio: below that the model throws from a reflect-pad
     *   inside the ECAPA convolution blocks instead of returning a poor result.
     */
    @SuppressLint("MissingPermission") // Checked via hasPermission() below.
    suspend fun record(seconds: Float = DEFAULT_SECONDS): Result =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext Result.PermissionDenied

            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) {
                return@withContext Result.Failed("device rejected 16 kHz mono PCM")
            }
            // Generous room so a scheduling hiccup cannot drop frames mid-passphrase.
            val bufferBytes = maxOf(minBuffer * 4, SAMPLE_RATE * BYTES_PER_SAMPLE)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufferBytes,
                )
            } catch (e: IllegalArgumentException) {
                return@withContext Result.Failed("could not open recorder: ${e.message}")
            }

            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    return@withContext Result.Failed("recorder did not initialize")
                }

                val wanted = (SAMPLE_RATE * seconds).toInt()
                val pcm = ShortArray(wanted)
                var filled = 0

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    return@withContext Result.Failed("microphone is busy")
                }

                while (filled < wanted) {
                    val read = recorder.read(pcm, filled, wanted - filled)
                    if (read <= 0) {
                        // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT: another app took
                        // the mic, or the record session died. Keep what we have.
                        Log.w(TAG, "read returned $read after $filled samples")
                        break
                    }
                    filled += read
                }
                recorder.stop()

                val captured = filled.toFloat() / SAMPLE_RATE
                if (filled < MIN_SAMPLES) {
                    return@withContext Result.TooShort(captured)
                }

                val samples = FloatArray(filled)
                var sumOfSquares = 0.0
                for (i in 0 until filled) {
                    // Divide by 32768 so the range is exactly [-1, 1); this is the
                    // normalization the exported graph was verified against.
                    val value = pcm[i] / PCM_FULL_SCALE
                    samples[i] = value
                    sumOfSquares += (value * value).toDouble()
                }
                val rms = sqrt(sumOfSquares / filled).toFloat()
                Log.i(TAG, "captured %.2fs, rms %.4f".format(captured, rms))
                Result.Captured(samples, rms)
            } catch (e: IllegalStateException) {
                Result.Failed("recording failed: ${e.message}")
            } finally {
                runCatching { recorder.release() }
            }
        }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val DEFAULT_SECONDS = 3.0f

        /**
         * Hard floor from the model, measured in M2: below 800 samples the export
         * throws. Well under any spoken passphrase, so this only guards mistaps and
         * interrupted recordings.
         */
        const val MIN_SECONDS = 0.5f

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val PCM_FULL_SCALE = 32_768.0f
        private const val MIN_SAMPLES = (SAMPLE_RATE * MIN_SECONDS).toInt()
        private const val TAG = "AudioCapture"
    }
}
