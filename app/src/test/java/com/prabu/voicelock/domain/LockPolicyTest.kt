package com.prabu.voicelock.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {

    private val chrome = "com.android.chrome"
    private val locked = setOf(chrome)
    private val now = 1_000_000L

    @Test
    fun `locks a locked app with no grant`() {
        assertTrue(
            LockPolicy.shouldLock(
                packageName = chrome,
                lockingEnabled = true,
                lockedPackages = locked,
                unlockedUntilMillis = null,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `does not lock an app that is not in the locked set`() {
        assertFalse(
            LockPolicy.shouldLock(
                packageName = "com.example.notes",
                lockingEnabled = true,
                lockedPackages = locked,
                unlockedUntilMillis = null,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `does not lock when locking is disabled globally`() {
        assertFalse(
            LockPolicy.shouldLock(
                packageName = chrome,
                lockingEnabled = false,
                lockedPackages = locked,
                unlockedUntilMillis = null,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `does not lock while a grant is still live`() {
        assertFalse(
            LockPolicy.shouldLock(
                packageName = chrome,
                lockingEnabled = true,
                lockedPackages = locked,
                unlockedUntilMillis = now + 1,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `locks once the grant has expired`() {
        assertTrue(
            LockPolicy.shouldLock(
                packageName = chrome,
                lockingEnabled = true,
                lockedPackages = locked,
                unlockedUntilMillis = now - 1,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `a grant expiring exactly now is expired`() {
        assertTrue(
            LockPolicy.shouldLock(
                packageName = chrome,
                lockingEnabled = true,
                lockedPackages = locked,
                unlockedUntilMillis = now,
                nowMillis = now,
            ),
        )
    }
}
