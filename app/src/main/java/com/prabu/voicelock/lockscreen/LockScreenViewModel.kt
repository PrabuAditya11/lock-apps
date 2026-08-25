package com.prabu.voicelock.lockscreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabu.voicelock.data.prefs.SettingsStore
import com.prabu.voicelock.domain.UnlockSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LockScreenViewModel @Inject constructor(
    private val unlockSessionManager: UnlockSessionManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _unlocked = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the package name once a grant has been recorded for it. */
    val unlocked: SharedFlow<String> = _unlocked

    /**
     * M1 has no voice check: pressing the button is the whole challenge. The grant is
     * written before the event is emitted so the app is already unlocked by the time the
     * activity finishes and the service sees it come back to the foreground.
     */
    fun unlock(packageName: String) {
        viewModelScope.launch {
            val graceSeconds = settingsStore.gracePeriodSeconds.first()
            unlockSessionManager.grant(packageName, graceSeconds.toLong() * 1_000L)
            _unlocked.emit(packageName)
        }
    }
}
