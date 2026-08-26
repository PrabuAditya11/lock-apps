package com.prabu.voicelock.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * Runs the exported ECAPA-TDNN graph to turn a waveform into a speaker embedding.
 *
 * Never touches the main thread: session creation and inference both run on
 * [Dispatchers.Default], and the session is single-threaded, as one utterance at
 * a time gains nothing from intra-op parallelism.
 *
 * The graph was exported with a fixed batch of 1 and a dynamic time axis, and
 * ONNX Runtime rejects any other batch outright rather than returning nonsense.
 */
@Singleton
class SpeakerEmbedder @Inject constructor(
    private val modelProvider: ModelProvider,
) {

    sealed interface Result {
        data class Embedded(
            val embedding: FloatArray,
            val inferenceMillis: Long,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    private val mutex = Mutex()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null

    /**
     * @param samples float32 mono at [AudioCapture.SAMPLE_RATE], normalized to [-1, 1].
     */
    suspend fun embed(samples: FloatArray): Result = withContext(Dispatchers.Default) {
        if (samples.size < MIN_SAMPLES) {
            return@withContext Result.Failed(
                "need at least $MIN_SAMPLES samples, got ${samples.size}",
            )
        }
        try {
            val active = ensureSession()
            var embedding: FloatArray
            val elapsed = measureTimeMillis {
                embedding = runInference(active, samples)
            }
            Log.i(TAG, "embedded ${samples.size} samples in ${elapsed}ms")
            Result.Embedded(embedding, elapsed)
        } catch (e: OrtException) {
            Log.e(TAG, "inference failed", e)
            Result.Failed("inference failed: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "model unavailable", e)
            Result.Failed("model unavailable: ${e.message}")
        }
    }

    private class ActiveSession(val environment: OrtEnvironment, val session: OrtSession)

    private suspend fun ensureSession(): ActiveSession = mutex.withLock {
        val existingEnvironment = environment
        val existingSession = session
        if (existingEnvironment != null && existingSession != null) {
            return@withLock ActiveSession(existingEnvironment, existingSession)
        }

        val modelPath = modelProvider.modelFile().absolutePath
        val newEnvironment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val elapsed = measureTimeMillis {
            // Path overload rather than bytes: lets the runtime map the ~80 MB file
            // instead of pinning it on the Java heap.
            session = newEnvironment.createSession(modelPath, options)
        }
        environment = newEnvironment
        Log.i(TAG, "session ready in ${elapsed}ms")
        ActiveSession(newEnvironment, requireNotNull(session))
    }

    private fun runInference(active: ActiveSession, samples: FloatArray): FloatArray {
        val shape = longArrayOf(1, samples.size.toLong())
        OnnxTensor.createTensor(
            active.environment,
            FloatBuffer.wrap(samples),
            shape,
        ).use { input ->
            active.session.run(mapOf(INPUT_NAME to input)).use { outputs ->
                val raw = outputs[0].value
                @Suppress("UNCHECKED_CAST")
                val matrix = raw as? Array<FloatArray>
                    ?: error("unexpected output type ${raw?.javaClass?.name}")
                return matrix[0].copyOf()
            }
        }
    }

    /** Releases native resources. The environment is process-wide and stays put. */
    suspend fun close() = mutex.withLock {
        runCatching { session?.close() }
        session = null
    }

    companion object {
        const val EMBEDDING_SIZE = 192
        private const val INPUT_NAME = "waveform"
        private const val MIN_SAMPLES = 800 // M2: the export throws below this.
        private const val TAG = "SpeakerEmbedder"
    }
}
