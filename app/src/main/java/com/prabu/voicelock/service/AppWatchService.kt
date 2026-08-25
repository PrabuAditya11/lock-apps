package com.prabu.voicelock.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.prabu.voicelock.data.LockedAppsRepository
import com.prabu.voicelock.data.prefs.SettingsStore
import com.prabu.voicelock.domain.LockPolicy
import com.prabu.voicelock.domain.UnlockSessionManager
import com.prabu.voicelock.lockscreen.LockScreenActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Watches foreground app changes and puts the lock screen in front of locked apps.
 *
 * foreground event -> decision -> lock screen visible must finish in well under 100 ms,
 * or the user sees the locked app content before the lock covers it. So on this path
 * there are no Room queries, no DataStore reads and no suspend calls before
 * startActivity: every input to the decision is already held in memory here, kept fresh
 * by the collectors started in [onServiceConnected].
 *
 * There is deliberately no BOOT_COMPLETED receiver -- the system rebinds an enabled
 * AccessibilityService after reboot.
 */
@AndroidEntryPoint
class AppWatchService : AccessibilityService() {

    @Inject lateinit var lockedAppsRepository: LockedAppsRepository
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var unlockSessionManager: UnlockSessionManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var lockedPackages: Set<String> = emptySet()
    @Volatile private var gracePeriodSeconds: Int = SettingsStore.DEFAULT_GRACE_SECONDS
    @Volatile private var lockingEnabled: Boolean = true

    private val tracker by lazy { ForegroundPackageTracker(packageName) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // First emissions arrive asynchronously, so there is a brief window right after
        // the service connects where lockedPackages is still empty and nothing locks.
        serviceScope.launch {
            lockedAppsRepository.lockedPackages.collect { lockedPackages = it }
        }
        serviceScope.launch {
            settingsStore.gracePeriodSeconds.collect { gracePeriodSeconds = it }
        }
        serviceScope.launch {
            settingsStore.lockingEnabled.collect { lockingEnabled = it }
        }
        Log.i(TAG, "AppWatchService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val now = System.currentTimeMillis()
        val transition = tracker.onWindowStateChanged(event.packageName, now) ?: return
        val graceMillis = gracePeriodSeconds.toLong() * 1_000L

        // The app being left keeps its grant alive, restarting the countdown from now, so
        // the grace period is measured from when the user left it.
        transition.previousPackage?.let {
            unlockSessionManager.refreshIfActive(it, graceMillis, now)
        }

        val target = transition.currentPackage
        val shouldLock = LockPolicy.shouldLock(
            packageName = target,
            lockingEnabled = lockingEnabled,
            lockedPackages = lockedPackages,
            unlockedUntilMillis = unlockSessionManager.unlockedUntil(target),
            nowMillis = now,
        )

        if (shouldLock) {
            startActivity(LockScreenActivity.newIntent(this, target))
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AppWatchService"
    }
}
