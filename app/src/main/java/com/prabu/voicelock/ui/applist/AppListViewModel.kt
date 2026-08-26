package com.prabu.voicelock.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabu.voicelock.audio.ModelSelfTest
import com.prabu.voicelock.data.LockedAppsRepository
import com.prabu.voicelock.data.prefs.SettingsStore
import com.prabu.voicelock.util.InstalledApp
import com.prabu.voicelock.util.InstalledAppsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val modelSelfTest: ModelSelfTest,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        /** Apps matching [query]; not the full installed list. */
        val apps: List<InstalledApp> = emptyList(),
        val lockedPackages: Set<String> = emptySet(),
        val lockingEnabled: Boolean = true,
        val gracePeriodSeconds: Int = SettingsStore.DEFAULT_GRACE_SECONDS,
        val query: String = "",
        /** Number of launchable apps before filtering, for the empty-result message. */
        val totalAppCount: Int = 0,
    )

    private val installedApps = MutableStateFlow<List<InstalledApp>?>(null)
    private val searchQuery = MutableStateFlow("")

    val uiState: StateFlow<UiState> = combine(
        installedApps,
        lockedAppsRepository.lockedPackages,
        settingsStore.lockingEnabled,
        settingsStore.gracePeriodSeconds,
        searchQuery,
    ) { apps, locked, enabled, grace, query ->
        val allApps = apps.orEmpty()
        val needle = query.trim()
        val matches = if (needle.isEmpty()) {
            allApps
        } else {
            // Package name is searchable too: several Samsung apps share a label.
            allApps.filter {
                it.label.contains(needle, ignoreCase = true) ||
                    it.packageName.contains(needle, ignoreCase = true)
            }
        }
        UiState(
            loading = apps == null,
            apps = matches,
            lockedPackages = locked,
            lockingEnabled = enabled,
            gracePeriodSeconds = grace,
            query = query,
            totalAppCount = allApps.size,
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

    private val _selfTestResult = MutableStateFlow<String?>(null)

    /** Debug-only: output of running the model on a fixed waveform. */
    val selfTestResult: StateFlow<String?> = _selfTestResult.asStateFlow()

    fun runSelfTest() {
        viewModelScope.launch {
            _selfTestResult.value = "running…"
            _selfTestResult.value = modelSelfTest.run().fold(
                onSuccess = { it.summary() },
                onFailure = { "failed: ${it.message}" },
            )
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
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
