package com.prabu.voicelock.domain

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ledger of package -> unlocked-until-epoch-millis.
 *
 * Never persisted. A reboot clears every grant, which is the intended security
 * property rather than a bug.
 *
 * Without this the lock re-triggers on tab switches, rotations and dialogs inside an
 * app that is already unlocked. If you see repeated lock screens, look here first.
 *
 * Reads happen on the accessibility event thread and writes from the lock screen, so
 * the backing map is concurrent.
 */
@Singleton
class UnlockSessionManager @Inject constructor() {

    private val unlockedUntilMillis = ConcurrentHashMap<String, Long>()

    /** Opens a grant for [packageName] lasting [graceMillis] from [nowMillis]. */
    fun grant(
        packageName: String,
        graceMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        unlockedUntilMillis[packageName] = nowMillis + graceMillis
    }

    /** Deadline of the grant for [packageName], or null when there is none. */
    fun unlockedUntil(packageName: String): Long? = unlockedUntilMillis[packageName]

    /**
     * Restarts the grace countdown for a package that still holds a live grant, and
     * drops the entry once it has expired.
     *
     * Called when a package leaves the foreground, so the grace period is measured
     * from the moment the user left the app rather than from the original unlock.
     * Without this, using an app for longer than the grace period and then briefly
     * switching away would re-lock it immediately on return.
     */
    fun refreshIfActive(
        packageName: String,
        graceMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val deadline = unlockedUntilMillis[packageName] ?: return
        if (nowMillis < deadline) {
            unlockedUntilMillis[packageName] = nowMillis + graceMillis
        } else {
            unlockedUntilMillis.remove(packageName)
        }
    }

    fun revoke(packageName: String) {
        unlockedUntilMillis.remove(packageName)
    }

    fun clear() {
        unlockedUntilMillis.clear()
    }
}
