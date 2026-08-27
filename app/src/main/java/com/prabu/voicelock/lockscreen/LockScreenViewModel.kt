package com.prabu.voicelock.lockscreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabu.voicelock.audio.AudioCapture
import com.prabu.voicelock.audio.SpeakerEmbedder
import com.prabu.voicelock.data.prefs.EnrollmentStore
import com.prabu.voicelock.data.prefs.PinStore
import com.prabu.voicelock.data.prefs.SettingsStore
import com.prabu.voicelock.domain.UnlockSessionManager
import com.prabu.voicelock.domain.VoiceMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LockScreenViewModel @Inject constructor(
    private val audioCapture: AudioCapture,
    private val speakerEmbedder: SpeakerEmbedder,
    private val enrollmentStore: EnrollmentStore,
    private val pinStore: PinStore,
    private val unlockSessionManager: UnlockSessionManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    sealed interface VoiceState {
        data object Idle : VoiceState
        data object Recording : VoiceState
        data object Computing : VoiceState

        /** Above threshold; the grant has already been recorded. */
        data class Accepted(val similarity: Float, val inferenceMillis: Long) : VoiceState

        data class Rejected(val similarity: Float, val inferenceMillis: Long) : VoiceState
        data class Failed(val message: String) : VoiceState
    }

    data class UiState(
        val voice: VoiceState = VoiceState.Idle,
        val passphraseHint: String? = null,
        val pinMessage: String? = null,
        val pinEntryEnabled: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _unlocked = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the package name once a grant has been recorded for it. */
    val unlocked: SharedFlow<String> = _unlocked

    init {
        // The passphrase is shown as a reminder. It is not a secret from whoever is
        // holding the phone; the voice is the factor being checked.
        viewModelScope.launch {
            val enrollment = enrollmentStore.load()
            _uiState.update { it.copy(passphraseHint = enrollment?.passphrase) }
        }
    }

    fun captureVoice(packageName: String) {
        val current = _uiState.value.voice
        if (current is VoiceState.Recording || current is VoiceState.Computing) return

        viewModelScope.launch {
            val enrollment = enrollmentStore.load()
            if (enrollment == null) {
                fail("No voiceprint enrolled. Open VoiceLock to enroll.")
                return@launch
            }

            _uiState.update { it.copy(voice = VoiceState.Recording) }
            when (val captured = audioCapture.record()) {
                is AudioCapture.Result.PermissionDenied ->
                    fail("Microphone permission is not granted")

                is AudioCapture.Result.TooShort ->
                    fail("Only %.2fs captured; need at least %.1fs"
                        .format(captured.seconds, AudioCapture.MIN_SECONDS))

                is AudioCapture.Result.Failed -> fail(captured.reason)

                is AudioCapture.Result.Captured -> {
                    _uiState.update { it.copy(voice = VoiceState.Computing) }
                    when (val embedded = speakerEmbedder.embed(captured.samples)) {
                        is SpeakerEmbedder.Result.Failed -> fail(embedded.reason)
                        is SpeakerEmbedder.Result.Embedded -> {
                            val similarity = VoiceMatch.cosineSimilarity(
                                embedded.embedding,
                                enrollment.centroid,
                            )
                            if (VoiceMatch.isMatch(similarity)) {
                                grantAndAnnounce(packageName)
                                _uiState.update {
                                    it.copy(
                                        voice = VoiceState.Accepted(
                                            similarity,
                                            embedded.inferenceMillis,
                                        ),
                                    )
                                }
                            } else {
                                _uiState.update {
                                    it.copy(
                                        voice = VoiceState.Rejected(
                                            similarity,
                                            embedded.inferenceMillis,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun submitPin(packageName: String, pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(pinEntryEnabled = false, pinMessage = null) }
            val message = when (val result = pinStore.verify(pin)) {
                is PinStore.VerifyResult.Success -> {
                    grantAndAnnounce(packageName)
                    null
                }

                is PinStore.VerifyResult.NotSet ->
                    "No PIN is set. Open VoiceLock to set one."

                is PinStore.VerifyResult.Wrong ->
                    "Wrong PIN. ${result.attemptsUntilLockout} attempts left."

                is PinStore.VerifyResult.LockedOut ->
                    "Too many attempts. Wait ${result.secondsRemaining}s."
            }
            _uiState.update { it.copy(pinEntryEnabled = true, pinMessage = message) }
        }
    }

    private suspend fun grantAndAnnounce(packageName: String) {
        val graceSeconds = settingsStore.gracePeriodSeconds.first()
        // The grant is written before the event is emitted, so the app is already
        // unlocked by the time the service sees it return to the foreground.
        unlockSessionManager.grant(packageName, graceSeconds.toLong() * 1_000L)
        _unlocked.emit(packageName)
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(voice = VoiceState.Failed(message)) }
    }
}
