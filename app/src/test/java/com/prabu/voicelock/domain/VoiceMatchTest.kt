package com.prabu.voicelock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VoiceMatchTest {

    private val tolerance = 1e-5f

    @Test
    fun `identical vectors have similarity one`() {
        val vector = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1f, VoiceMatch.cosineSimilarity(vector, vector), tolerance)
    }

    @Test
    fun `opposite vectors have similarity minus one`() {
        val vector = floatArrayOf(1f, 2f, 3f)
        val opposite = floatArrayOf(-1f, -2f, -3f)
        assertEquals(-1f, VoiceMatch.cosineSimilarity(vector, opposite), tolerance)
    }

    @Test
    fun `orthogonal vectors have similarity zero`() {
        assertEquals(
            0f,
            VoiceMatch.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            tolerance,
        )
    }

    @Test
    fun `similarity ignores magnitude`() {
        val small = floatArrayOf(1f, 1f)
        val large = floatArrayOf(500f, 500f)
        assertEquals(1f, VoiceMatch.cosineSimilarity(small, large), tolerance)
    }

    @Test
    fun `a zero vector is not a match rather than undefined`() {
        val zero = floatArrayOf(0f, 0f)
        assertEquals(0f, VoiceMatch.cosineSimilarity(zero, floatArrayOf(1f, 1f)), tolerance)
    }

    @Test
    fun `normalizing produces unit length`() {
        val normalized = VoiceMatch.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalized[0], tolerance)
        assertEquals(0.8f, normalized[1], tolerance)
    }

    @Test
    fun `normalizing a zero vector leaves it alone`() {
        val normalized = VoiceMatch.l2Normalize(floatArrayOf(0f, 0f))
        assertEquals(0f, normalized[0], tolerance)
        assertEquals(0f, normalized[1], tolerance)
    }

    @Test
    fun `centroid of one embedding is that embedding normalized`() {
        val centroid = VoiceMatch.centroid(listOf(floatArrayOf(3f, 4f)))
        assertEquals(0.6f, centroid[0], tolerance)
        assertEquals(0.8f, centroid[1], tolerance)
    }

    @Test
    fun `centroid lies between its inputs`() {
        val centroid = VoiceMatch.centroid(
            listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
        )
        val expected = (1f / sqrt(2f))
        assertEquals(expected, centroid[0], tolerance)
        assertEquals(expected, centroid[1], tolerance)
    }

    @Test
    fun `centroid is unit length`() {
        val centroid = VoiceMatch.centroid(
            listOf(
                floatArrayOf(2f, 1f, 0f),
                floatArrayOf(1f, 3f, 1f),
                floatArrayOf(0f, 1f, 2f),
            ),
        )
        var sumOfSquares = 0f
        for (value in centroid) sumOfSquares += value * value
        assertEquals(1f, sumOfSquares, 1e-4f)
    }

    @Test
    fun `a loud sample does not dominate the centroid`() {
        // Same directions, wildly different magnitudes. Normalizing before averaging
        // is what keeps the quiet recording counting equally.
        val balanced = VoiceMatch.centroid(
            listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
        )
        val lopsided = VoiceMatch.centroid(
            listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 900f)),
        )
        assertEquals(balanced[0], lopsided[0], tolerance)
        assertEquals(balanced[1], lopsided[1], tolerance)
    }

    @Test
    fun `min pairwise similarity finds the worst pair`() {
        val consistent = floatArrayOf(1f, 0f)
        val alsoConsistent = floatArrayOf(0.99f, 0.14f)
        val outlier = floatArrayOf(0f, 1f)
        val lowest = VoiceMatch.minPairwiseSimilarity(
            listOf(consistent, alsoConsistent, outlier),
        )
        assertEquals(
            VoiceMatch.cosineSimilarity(consistent, outlier),
            lowest,
            tolerance,
        )
    }

    @Test
    fun `a single sample has nothing to disagree with`() {
        assertEquals(1f, VoiceMatch.minPairwiseSimilarity(listOf(floatArrayOf(1f, 2f))), tolerance)
    }

    @Test
    fun `match is inclusive at the threshold`() {
        assertTrue(VoiceMatch.isMatch(0.6f, threshold = 0.6f))
        assertFalse(VoiceMatch.isMatch(0.5999f, threshold = 0.6f))
    }
}
