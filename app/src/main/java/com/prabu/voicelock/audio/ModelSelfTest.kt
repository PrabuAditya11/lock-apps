package com.prabu.voicelock.audio

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs the model on a fixed, synthetic waveform so the device result can be
 * compared against the desktop run that M2 verified.
 *
 * This exists because "inference did not crash" is a much weaker claim than
 * "inference produced the same numbers". ONNX Runtime for Android is a different
 * build from the desktop package, and a kernel difference would show up as a
 * plausible-but-wrong embedding, which is the failure mode this whole milestone
 * keeps running into.
 *
 * tools/onnx/reference_vector.py generates the identical waveform and prints the
 * same statistics for comparison. The formula must stay in step with it: both
 * sides accumulate in double and narrow to float once, so the inputs agree to
 * the last bit.
 */
@Singleton
class ModelSelfTest @Inject constructor(
    private val speakerEmbedder: SpeakerEmbedder,
) {

    data class Report(
        val samples: Int,
        val inferenceMillis: Long,
        val norm: Float,
        val head: List<Float>,
    ) {
        fun summary(): String = buildString {
            append("%d samples, %d ms, norm %.4f\n".format(samples, inferenceMillis, norm))
            append(head.joinToString(", ") { "%.6f".format(it) })
        }
    }

    suspend fun run(): Result<Report> {
        val waveform = referenceWaveform()
        return when (val result = speakerEmbedder.embed(waveform)) {
            is SpeakerEmbedder.Result.Failed -> Result.failure(IllegalStateException(result.reason))
            is SpeakerEmbedder.Result.Embedded -> {
                var sumOfSquares = 0.0
                for (value in result.embedding) sumOfSquares += (value * value).toDouble()
                val report = Report(
                    samples = waveform.size,
                    inferenceMillis = result.inferenceMillis,
                    norm = sqrt(sumOfSquares).toFloat(),
                    head = result.embedding.take(HEAD_VALUES),
                )
                Log.i(TAG, "self-test: ${report.summary()}")
                Result.success(report)
            }
        }
    }

    /** Three-tone waveform; see the class docs on why the arithmetic is fixed. */
    private fun referenceWaveform(): FloatArray {
        val total = (AudioCapture.SAMPLE_RATE * SECONDS).toInt()
        return FloatArray(total) { index ->
            val t = index.toDouble() / AudioCapture.SAMPLE_RATE
            val value = 0.6 * sin(2.0 * PI * 220.0 * t) +
                0.3 * sin(2.0 * PI * 440.0 * t) +
                0.1 * sin(2.0 * PI * 880.0 * t)
            (value * 0.9).toFloat()
        }
    }

    private companion object {
        const val SECONDS = 3.0
        const val HEAD_VALUES = 8
        const val TAG = "ModelSelfTest"
    }
}
