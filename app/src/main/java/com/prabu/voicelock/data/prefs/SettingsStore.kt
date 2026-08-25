package com.prabu.voicelock.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val gracePeriodSeconds: Flow<Int> = context.settingsDataStore.data
        .map { it[Keys.GRACE_SECONDS] ?: DEFAULT_GRACE_SECONDS }
        .distinctUntilChanged()

    val lockingEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.LOCKING_ENABLED] ?: true }
        .distinctUntilChanged()

    suspend fun setGracePeriodSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[Keys.GRACE_SECONDS] = seconds }
    }

    suspend fun setLockingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LOCKING_ENABLED] = enabled }
    }

    private object Keys {
        val GRACE_SECONDS = intPreferencesKey("grace_period_seconds")
        val LOCKING_ENABLED = booleanPreferencesKey("locking_enabled")
    }

    companion object {
        const val DEFAULT_GRACE_SECONDS = 30
    }
}
