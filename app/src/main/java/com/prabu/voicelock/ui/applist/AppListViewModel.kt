package com.prabu.voicelock.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabu.voicelock.data.LockedAppsRepository
import com.prabu.voicelock.data.prefs.SettingsStore
import com.prabu.voicelock.util.InstalledApp
import com.prabu.voicelock.util.InstalledAppsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val installedAppsProvider: InstalledAppsProvider,
    private val lockedAppsRepository: LockedAppsRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val apps: List<InstalledApp> = emptyList(),
        val lockedPackages: Set<String> = emptySet(),
        val lockingEnabled: Boolean = true,
        val gracePeriodSeconds: Int = SettingsStore.DEFAULT_GRACE_SECONDS,
    )

    private val installedApps = MutableStateFlow<List<InstalledApp>?>(null)

    val uiState: StateFlow<UiState> = combine(
        installedApps,
        lockedAppsRepository.lockedPackages,
        settingsStore.lockingEnabled,
        settingsStore.gracePeriodSeconds,
    ) { apps, locked, enabled, grace ->
        UiState(
            loading = apps == null,
            apps = apps.orEmpty(),
            lockedPackages = locked,
            lockingEnabled = enabled,
            gracePeriodSeconds = grace,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(),
    )

    init {
        viewModelScope.launch {
            installedApps.value = installedAppsProvider.loadLaunchableApps()
        }
    }

    fun setLocked(packageName: String, locked: Boolean) {
        viewModelScope.launch { lockedAppsRepository.setLocked(packageName, locked) }
    }

    fun setLockingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setLockingEnabled(enabled) }
    }

    fun setGracePeriodSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setGracePeriodSeconds(seconds) }
    }
}
