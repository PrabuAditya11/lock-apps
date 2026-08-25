package com.prabu.voicelock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundPackageTrackerTest {

    private val ownPackage = "com.prabu.voicelock"
    private val chrome = "com.android.chrome"
    private val bank = "com.example.bank"
    private val launcher = "com.android.launcher3"

    private fun tracker() = ForegroundPackageTracker(ownPackage)

    @Test
    fun `first real package is a transition from null`() {
        val transition = tracker().onWindowStateChanged(chrome, 0L)
        assertEquals(ForegroundPackageTracker.Transition(null, chrome), transition)
    }

    @Test
    fun `repeat events for the foreground app are ignored`() {
        val tracker = tracker()
        tracker.onWindowStateChanged(chrome, 0L)
        assertNull(tracker.onWindowStateChanged(chrome, 50L))
        assertNull(tracker.onWindowStateChanged(chrome, 5_000L))
    }

    @Test
    fun `null and blank package names are ignored`() {
        val tracker = tracker()
        assertNull(tracker.onWindowStateChanged(null, 0L))
        assertNull(tracker.onWindowStateChanged("", 1L))
        assertNull(tracker.onWindowStateChanged("   ", 2L))
    }

    @Test
    fun `system ui is ignored and does not become the last seen package`() {
        val tracker = tracker()
        tracker.onWindowStateChanged(chrome, 0L)
        assertNull(tracker.onWindowStateChanged("com.android.systemui", 100L))
        // Chrome is still the foreground app, so returning to it is not a transition.
        assertNull(tracker.onWindowStateChanged(chrome, 200L))
    }

    @Test
    fun `our own lock screen is ignored so the locked app does not re-trigger`() {
        val tracker = tracker()
        tracker.onWindowStateChanged(bank, 0L)
        assertNull(tracker.onWindowStateChanged(ownPackage, 10L))
        // After unlocking, the locked app returning must not produce a new transition.
        assertNull(tracker.onWindowStateChanged(bank, 20L))
    }

    @Test
    fun `a real app switch reports the package that was left`() {
        val tracker = tracker()
        tracker.onWindowStateChanged(chrome, 0L)
        val transition = tracker.onWindowStateChanged(bank, 1_000L)
        assertEquals(ForegroundPackageTracker.Transition(chrome, bank), transition)
    }

    @Test
    fun `a flicker back to the previous app within the window is suppressed`() {
        val tracker = tracker()
        tracker.onWindowStateChanged(chrome, 0L)
        tracker.onWindowStateChanged(bank, 1_000L)
        assertNull(tracker.onWindowStateChanged(chrome, 1_100L))
    }

    @Test
    fun `returning to the previous app after the window is a real transition`() {
        val tracker = tracker()
        tracker.onWindowStateChanged(chrome, 0L)
        tracker.onWindowStateChanged(bank, 1_000L)
        val transition = tracker.onWindowStateChanged(chrome, 5_000L)
        assertEquals(ForegroundPackageTracker.Transition(bank, chrome), transition)
    }

    @Test
    fun `a fast switch to a third app is never suppressed`() {
        val tracker = tracker()
        tracker.onWindowStateChanged(chrome, 0L)
        tracker.onWindowStateChanged(launcher, 10L)
        // Reaching a locked app quickly through the launcher must still be reported.
        val transition = tracker.onWindowStateChanged(bank, 20L)
        assertEquals(ForegroundPackageTracker.Transition(launcher, bank), transition)
    }
}
