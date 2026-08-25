package com.prabu.voicelock.domain

/**
 * The unlock decision, as a pure function so it can be unit tested without a device.
 *
 * Everything it needs is passed in; it reads no repository, preference store or clock.
 * [com.prabu.voicelock.service.AppWatchService] supplies in-memory state so the call
 * stays synchronous on the latency-critical path.
 */
object LockPolicy {

    /**
     * @param unlockedUntilMillis deadline of an existing unlock grant for [packageName],
     *   or null if there is no grant. See [UnlockSessionManager].
     */
    fun shouldLock(
        packageName: String,
        lockingEnabled: Boolean,
        lockedPackages: Set<String>,
        unlockedUntilMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (!lockingEnabled) return false
        if (packageName !in lockedPackages) return false
        // A grant that expires exactly now is expired: the comparison is strict.
        if (unlockedUntilMillis != null && nowMillis < unlockedUntilMillis) return false
        return true
    }
}
