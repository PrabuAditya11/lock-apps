package com.prabu.voicelock.ui.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabu.voicelock.audio.AudioCapture
import com.prabu.voicelock.audio.SpeakerEmbedder
import com.prabu.voicelock.data.prefs.EnrollmentStore
import com.prabu.voicelock.data.prefs.PassphraseLanguage
import com.prabu.voicelock.domain.VoiceMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives enrollment: language, then passphrase, then [REQUIRED_SAMPLES] recordings.
 *
 * Language is chosen first on purpose. The passphrase is typed and spoken under a
 * known language, and changing it later invalidates both, so it cannot be a
 * settings toggle.
 */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    private val audioCapture: AudioCapture,
    private val speakerEmbedder: SpeakerEmbedder,
    private val enrollmentStore: EnrollmentStore,
) : ViewModel() {

    enum class Step { LANGUAGE, PASSPHRASE, RECORDING, DONE }

    data class UiState(
        val step: Step = Step.LANGUAGE,
        val language: PassphraseLanguage? = null,
        val passphrase: String = "",
        val capturedSamples: Int = 0,
        val busy: Boolean = false,
        val message: String? = null,
        /** Set once all samples are in; below the gate it is a warning, not a block. */
        val consistency: Float? = null,
        val inconsistent: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Embeddings held in memory only; the audio itself is discarded immediately. */
    private val samples = mutableListOf<FloatArray>()

    fun chooseLanguage(language: PassphraseLanguage) {
        _uiState.update { it.copy(language = language, step = Step.PASSPHRASE, message = null) }
    }

    fun submitPassphrase(passphrase: String) {
        val trimmed = passphrase.trim()
        if (trimmed.length < MIN_PASSPHRASE_LENGTH) {
            _uiState.update {
                it.copy(message = "Use at least $MIN_PASSPHRASE_LENGTH characters")
            }
            return
        }
        samples.clear()
        _uiState.update {
            it.copy(
                passphrase = trimmed,
                step = Step.RECORDING,
                capturedSamples = 0,
                message = null,
            )
        }
    }

    /** Records one utterance and keeps its embedding. */
    fun recordSample() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "Listening…") }

            when (val captured = audioCapture.record()) {
                is AudioCapture.Result.PermissionDenied ->
                    finishSample("Microphone permission is not granted")

                is AudioCapture.Result.TooShort ->
                    finishSample("Only %.2fs captured; speak for the full 3 seconds"
                        .format(captured.seconds))

                is AudioCapture.Result.Failed -> finishSample(captured.reason)

                is AudioCapture.Result.Captured -> {
                    _uiState.update { it.copy(message = "Computing…") }
                    when (val embedded = speakerEmbedder.embed(captured.samples)) {
                        is SpeakerEmbedder.Result.Failed -> finishSample(embedded.reason)
                        is SpeakerEmbedder.Result.Embedded -> {
                            samples += embedded.embedding
                            val consistency = VoiceMatch.minPairwiseSimilarity(samples)
                            _uiState.update {
                                it.copy(
                                    busy = false,
                                    capturedSamples = samples.size,
                                    message = null,
                                    consistency = consistency.takeIf { samples.size >= 2 },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun discardLastSample() {
        if (samples.isEmpty()) return
        samples.removeAt(samples.lastIndex)
        _uiState.update {
            it.copy(
                capturedSamples = samples.size,
                consistency = VoiceMatch.minPairwiseSimilarity(samples).takeIf { samples.size >= 2 },
                inconsistent = false,
                message = null,
            )
        }
    }

    /**
     * Builds the centroid and stores it.
     *
     * A low-consistency set is surfaced once as a warning rather than refused: only
     * the user can tell whether the recordings were genuinely bad or the threshold
     * is simply wrong, and that threshold is not calibrated yet.
     */
    fun save(force: Boolean = false) {
        val state = _uiState.value
        val language = state.language ?: return
        if (samples.size < REQUIRED_SAMPLES) return

        val consistency = VoiceMatch.minPairwiseSimilarity(samples)
        if (!force && consistency < VoiceMatch.MIN_ENROLLMENT_CONSISTENCY) {
            _uiState.update {
                it.copy(
                    inconsistent = true,
                    consistency = consistency,
                    message = "These recordings differ a lot from each other " +
                        "(%.2f). Re-record, or save anyway.".format(consistency),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "Saving…") }
            enrollmentStore.save(
                EnrollmentStore.Enrollment(
                    language = language,
                    passphrase = state.passphrase,
                    centroid = VoiceMatch.centroid(samples),
                    sampleCount = samples.size,
                    consistency = consistency,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
            // Embeddings are derived from audio; drop them once persisted.
            samples.clear()
            _uiState.update {
                it.copy(step = Step.DONE, busy = false, message = null, inconsistent = false)
            }
        }
    }

    fun restart() {
        samples.clear()
        _uiState.value = UiState()
    }

    private fun finishSample(message: String) {
        _uiState.update { it.copy(busy = false, message = message) }
    }

    companion object {
        const val REQUIRED_SAMPLES = 3
        const val MIN_PASSPHRASE_LENGTH = 4
    }
}
