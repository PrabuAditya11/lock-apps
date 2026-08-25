package com.prabu.voicelock.service

/**
 * Turns a noisy stream of TYPE_WINDOW_STATE_CHANGED events into genuine foreground
 * package transitions.
 *
 * Event noise is the top source of bugs here: the event fires for toasts, IME popups,
 * dialogs and notification shade pulls as well as for real app switches.
 *
 * Holds mutable state but touches no Android APIs, so it is unit testable. The caller
 * passes the clock in.
 */
class ForegroundPackageTracker(
    private val ownPackageName: String,
    private val flickerSuppressionMillis: Long = DEFAULT_FLICKER_SUPPRESSION_MILLIS,
) {

    data class Transition(
        /** Package that just left the foreground, or null for the first transition. */
        val previousPackage: String?,
        val currentPackage: String,
    )

    /** Package currently believed to be in the foreground. */
    private var lastPackage: String? = null

    /** Package in the foreground before [lastPackage]; used only by the flicker guard. */
    private var packageBeforeLast: String? = null

    private var lastTransitionAtMillis: Long = 0L

    /**
     * @return the transition to act on, or null when the event is noise.
     */
    fun onWindowStateChanged(packageName: CharSequence?, nowMillis: Long): Transition? {
        val candidate = packageName?.toString()?.trim()
        if (candidate.isNullOrEmpty()) return null

        // Our own lock screen must not count as a foreground change. Ignoring it without
        // touching lastPackage is what stops the lock screen from re-triggering on itself:
        // when the locked app comes back after an unlock it still matches lastPackage.
        if (candidate == ownPackageName) return null
        if (candidate in IGNORED_PACKAGES) return null

        val previous = lastPackage

        // Repeat events for the app already in the foreground. This is the filter that
        // absorbs dialogs, rotations and tab switches inside one app.
        if (candidate == previous) return null

        // Flicker guard: a window from another package appearing in front of the current
        // one and immediately handing focus back (A -> B -> A within the window). The
        // lock state of A was resolved moments ago, so re-deciding it is pointless work
        // and can double-launch the lock screen. A switch to any package we did not just
        // come from is never suppressed, so a real app switch always gets through.
        if (candidate == packageBeforeLast &&
            nowMillis - lastTransitionAtMillis < flickerSuppressionMillis
        ) {
            return null
        }

        packageBeforeLast = previous
        lastPackage = candidate
        lastTransitionAtMillis = nowMillis
        return Transition(previousPackage = previous, currentPackage = candidate)
    }

    companion object {
        const val DEFAULT_FLICKER_SUPPRESSION_MILLIS = 250L

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
        )
    }
}
