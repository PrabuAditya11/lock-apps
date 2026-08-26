package com.prabu.voicelock.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabu.voicelock.data.prefs.PinStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val pinStore: PinStore,
) : ViewModel() {

    val isPinSet: StateFlow<Boolean> = pinStore.isPinSet.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // Assume unset until the store answers; the gate re-evaluates on emission.
        initialValue = false,
    )

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    fun setPin(pin: String) {
        viewModelScope.launch {
            _pinError.value = when {
                pin.length < PinStore.MIN_PIN_LENGTH ->
                    "Use at least ${PinStore.MIN_PIN_LENGTH} digits"

                !pin.all { it.isDigit() } -> "Digits only"
                else -> {
                    // PBKDF2 at 120k iterations takes a moment; PinStore moves it off
                    // the main thread itself.
                    runCatching { pinStore.setPin(pin) }.exceptionOrNull()?.message
                }
            }
        }
    }
}
