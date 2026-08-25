package com.prabu.voicelock.data

import com.prabu.voicelock.data.local.LockedAppDao
import com.prabu.voicelock.data.local.LockedAppEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockedAppsRepository @Inject constructor(
    private val dao: LockedAppDao,
) {
    val lockedPackages: Flow<Set<String>> =
        dao.observeLockedPackages()
            .map { it.toSet() }
            .distinctUntilChanged()

    suspend fun setLocked(packageName: String, locked: Boolean) {
        if (locked) {
            dao.insert(LockedAppEntity(packageName, System.currentTimeMillis()))
        } else {
            dao.delete(packageName)
        }
    }
}
