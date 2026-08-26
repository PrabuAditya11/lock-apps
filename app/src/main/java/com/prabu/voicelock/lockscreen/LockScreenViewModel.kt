package com.prabu.voicelock.lockscreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabu.voicelock.audio.AudioCapture
import com.prabu.voicelock.audio.SpeakerEmbedder
import com.prabu.voicelock.data.prefs.PinStore
import com.prabu.voicelock.data.prefs.SettingsStore
import com.prabu.voicelock.domain.UnlockSessionManager
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
    private val pinStore: PinStore,
    private val unlockSessionManager: UnlockSessionManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /**
     * M3 computes an embedding but cannot decide anything with it: there is no
     * enrolled reference to compare against until M4, so [Embedded] is a
     * diagnostic and the PIN is the only way through.
     */
    sealed interface VoiceState {
        data object Idle : VoiceState
        data object Recording : VoiceState
        data object Computing : VoiceState
        data class Embedded(
            val dimensions: Int,
            val inferenceMillis: Long,
            val rms: Float,
            val norm: Float,
        ) : VoiceState

        data class Failed(val message: String) : VoiceState
    }

    data class UiState(
        val voice: VoiceState = VoiceState.Idle,
        val pinMessage: String? = null,
        val pinEntryEnabled: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _unlocked = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the package name once a grant has been recorded for it. */
    val unlocked: SharedFlow<String> = _unlocked

    fun captureVoice() {
        if (_uiState.value.voice is VoiceState.Recording) return
        viewModelScope.launch {
            _uiState.update { it.copy(voice = VoiceState.Recording) }

            when (val captured = audioCapture.record()) {
                is AudioCapture.Result.PermissionDenied ->
                    fail("Microphone permission is not granted")

                is AudioCapture.Result.TooShort ->
                    fail("Only %.2fs captured; need at least %.1fs".format(
                        captured.seconds, AudioCapture.MIN_SECONDS,
                    ))

                is AudioCapture.Result.Failed -> fail(captured.reason)

                is AudioCapture.Result.Captured -> {
                    _uiState.update { it.copy(voice = VoiceState.Computing) }
                    when (val embedded = speakerEmbedder.embed(captured.samples)) {
                        is SpeakerEmbedder.Result.Failed -> fail(embedded.reason)
                        is SpeakerEmbedder.Result.Embedded -> {
                            var sumOfSquares = 0.0
                            for (value in embedded.embedding) {
                                sumOfSquares += (value * value).toDouble()
                            }
                            _uiState.update {
                                it.copy(
                                    voice = VoiceState.Embedded(
                                        dimensions = embedded.embedding.size,
                                        inferenceMillis = embedded.inferenceMillis,
                                        rms = captured.rms,
                                        norm = Math.sqrt(sumOfSquares).toFloat(),
                                    ),
                                )
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
