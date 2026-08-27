package com.prabu.voicelock.domain

import kotlin.math.sqrt

/**
 * Embedding arithmetic for speaker verification. Pure, so it is unit tested
 * without a device.
 *
 * Everything here operates on the 192-dim ECAPA-TDNN embeddings produced by
 * `audio.SpeakerEmbedder`; nothing here knows how they were produced.
 */
object VoiceMatch {

    /**
     * Cosine similarity, the score speaker verification is built on. Ranges
     * [-1, 1]; identical direction is 1.
     *
     * @return 0 when either vector has no magnitude, since an undefined angle is
     *   not a match.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var aSquared = 0.0
        var bSquared = 0.0
        for (i in a.indices) {
            dot += (a[i].toDouble() * b[i])
            aSquared += (a[i].toDouble() * a[i])
            bSquared += (b[i].toDouble() * b[i])
        }
        val denominator = sqrt(aSquared) * sqrt(bSquared)
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    /** Scales [vector] to unit length, or returns it unchanged when it has none. */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumOfSquares = 0.0
        for (value in vector) sumOfSquares += (value.toDouble() * value)
        val magnitude = sqrt(sumOfSquares)
        if (magnitude == 0.0) return vector.copyOf()
        return FloatArray(vector.size) { (vector[it] / magnitude).toFloat() }
    }

    /**
     * The enrolled voiceprint: the mean direction of several utterances.
     *
     * Each embedding is normalized *before* averaging so a loud recording does not
     * outweigh a quiet one, and the mean is normalized again so the result is a
     * direction comparable by dot product.
     */
    fun centroid(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty()) { "cannot build a centroid from no embeddings" }
        val size = embeddings.first().size
        require(embeddings.all { it.size == size }) { "embeddings differ in size" }

        val sum = DoubleArray(size)
        for (embedding in embeddings) {
            val unit = l2Normalize(embedding)
            for (i in 0 until size) sum[i] += unit[i]
        }
        return l2Normalize(FloatArray(size) { (sum[it] / embeddings.size).toFloat() })
    }

    /**
     * Lowest similarity between any two enrollment samples.
     *
     * Used as an enrollment quality gate: if the samples do not resemble each
     * other, the centroid is being built from inconsistent recordings and
     * verification will behave badly no matter what the threshold is.
     *
     * @return 1 for a single sample, which has nothing to disagree with.
     */
    fun minPairwiseSimilarity(embeddings: List<FloatArray>): Float {
        if (embeddings.size < 2) return 1f
        var lowest = Float.MAX_VALUE
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                val similarity = cosineSimilarity(embeddings[i], embeddings[j])
                if (similarity < lowest) lowest = similarity
            }
        }
        return lowest
    }

    fun isMatch(similarity: Float, threshold: Float = PROVISIONAL_THRESHOLD): Boolean =
        similarity >= threshold

    /**
     * **Provisional. Not calibrated.**
     *
     * A placeholder so the flow is testable end to end. CLAUDE.md requires this be
     * set from measured FAR/FRR on real recordings — same speaker for false
     * rejections, other speakers for false acceptances — and until that happens
     * this number is a guess, not a security property.
     */
    const val PROVISIONAL_THRESHOLD = 0.60f

    /** Below this, enrollment samples are too inconsistent to trust. Also provisional. */
    const val MIN_ENROLLMENT_CONSISTENCY = 0.55f
}
