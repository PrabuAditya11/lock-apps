# VoiceLock — project constraints

App-locker for Android. Locks selected apps behind a voice challenge: the user must speak a
passphrase **and** be recognized as the enrolled speaker. Native Kotlin, Android only.

---

## Hard constraints

- **Kotlin only.** No React Native, no Flutter, no JS. Do not add a cross-platform layer.
- **Android only.** No iOS considerations, no `expect/actual`, no KMP.
- `minSdk 26` — `TYPE_APPLICATION_OVERLAY` and adaptive icons require it.
- `compileSdk` / `targetSdk 36`. Verify current Play requirement before changing.
- Jetpack Compose for all UI. No XML layouts except the manifest and `accessibility_service_config.xml`.
- Hilt for DI. Room for persistence. Coroutines + Flow for async. No RxJava.
- **Verify library versions before writing a dependency.** Do not trust versions from memory —
  check the current release and tell me what you picked.

---

## Architecture

```
com.yourname.voicelock/
├── VoiceLockApp.kt                 @HiltAndroidApp
├── data/
│   ├── local/                      Room: LockedAppEntity, DAO, Database
│   ├── prefs/SettingsStore.kt      gracePeriodSeconds, lockingEnabled
│   └── LockedAppsRepository.kt     exposes Flow<Set<String>>
├── domain/
│   ├── UnlockSessionManager.kt     @Singleton, in-memory unlock ledger
│   └── LockPolicy.kt               pure: shouldLock(pkg, now) — unit tested
├── service/
│   ├── AppWatchService.kt          AccessibilityService
│   └── ForegroundPackageTracker.kt debounce + event noise filtering
├── lockscreen/
│   ├── LockScreenActivity.kt
│   └── LockScreenViewModel.kt
├── ui/                             MainActivity, onboarding, applist, theme
└── util/InstalledAppsProvider.kt
```

### The unlock path is latency-critical

`foreground event → decision → lock screen visible` must complete in **under ~100 ms**, or the
user sees the locked app's content before the lock covers it.

Therefore, on this path:

- **No Room queries.** `AppWatchService` holds the locked-package set in memory, kept fresh by
  collecting `LockedAppsRepository.lockedPackages` in the service's own scope.
- **No DataStore reads.** Cache `gracePeriodSeconds` in memory the same way.
- **No suspend calls before `startActivity`.** The decision is synchronous.

### UnlockSessionManager

`@Singleton`, holds `MutableMap<String, Long>` of package → unlocked-until-epoch-millis.
**In-memory only — never persisted.** A reboot must clear all grants; that is the intended
security property, not a bug.

Without this, the lock re-triggers on tab switches, rotations, and dialogs inside an already
unlocked app. If you see repeated lock screens, check here first.

---

## Lock screen: Activity, not overlay

Use a full-screen `Activity`, **not** a `WindowManager` overlay. Reasons:

- Compose needs `LifecycleOwner` + `SavedStateRegistryOwner`, which a `Service`-hosted
  `ComposeView` does not have.
- Back-button and focus handling are standard on an Activity.

Required manifest attributes — all four matter:

```xml
<activity
    android:name=".lockscreen.LockScreenActivity"
    android:launchMode="singleInstance"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:exported="false" />
```

`taskAffinity=""` is not optional. Without it the lock screen joins the locked app's task stack
and the user can swipe back past it.

Also required:

- `FLAG_SECURE` on the window — blocks screenshots and recents thumbnails.
- Back press → `Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)`. **Never `finish()`** —
  that returns the user to the locked app.
- Request `SYSTEM_ALERT_WINDOW` even though we don't draw an overlay. It exempts us from
  Android 10+ background-activity-launch restrictions so `startActivity` from the service fires.

---

## AccessibilityService config

```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="false" />
```

`canRetrieveWindowContent="false"` is deliberate. `event.packageName` is all we need. Do not
enable screen-content access — it is unnecessary and makes the privacy story worse.

**Event noise is the top source of bugs.** `TYPE_WINDOW_STATE_CHANGED` fires for toasts, IME
popups, dialogs, and notification shade pulls. Filter in `ForegroundPackageTracker`:

- ignore `com.android.systemui` and our own package
- ignore null/blank `packageName`
- only act when the package differs from the last-seen package

No `BOOT_COMPLETED` receiver. The system rebinds an enabled `AccessibilityService` after reboot.

---

## Audio and inference (later milestones — do not build yet)

- Speaker verification: ECAPA-TDNN exported from SpeechBrain to ONNX, run via ONNX Runtime Android.
- Capture via `AudioRecord`: **16 kHz, mono, 16-bit PCM**, converted to float32 normalized to
  [-1, 1]. A sample-rate or normalization mismatch silently destroys accuracy rather than erroring.
- Mel-filterbank features must match SpeechBrain's `Fbank` config exactly. Prefer tracing the
  frontend into the ONNX graph over reimplementing it in Kotlin.
- **Inference never on the main thread.** `Dispatchers.Default`, single-threaded session.
- Enrollment embedding stored via `EncryptedSharedPreferences` / Android Keystore.
  **Never store recorded audio.** Discard the buffer after computing the embedding.
- PIN fallback is mandatory from the first audio milestone. Voice will produce false rejections.

---

## Roadmap

- [ ] **M1 — lock mechanism (current).** Detect foreground app, block it, hardcoded unlock button.
      No audio, no ML, no model files.
- [ ] M2 — ONNX export verified: embeddings match the Python original on the same wav.
- [ ] M3 — `AudioRecord` capture + on-device inference wired to the lock screen.
- [ ] M4 — enrollment flow, threshold calibration against my own FAR/FRR measurements.
- [ ] M5 — keyword/passphrase check.
- [ ] M6 — quantization, only if APK size is an actual problem.

**Do not build ahead of the current milestone.** If a task seems to need a later milestone,
say so rather than stubbing it.

### M1 exit criteria

All six must pass on a physical device:

1. Lock Chrome, open Chrome → lock screen appears with no flash of Chrome's content.
2. Unlock → switch tabs, rotate, open a dialog → no re-lock.
3. Home, reopen within grace period → no lock. After expiry → locks.
4. Back button and recents cannot bypass.
5. Survives a reboot with no re-setup.
6. Survives 30 minutes idle.

---

## Git

- **Never commit or push until I explicitly say so.** Finishing a task is not a reason to
  commit. Leave the work in the working tree and tell me what changed; I decide when it
  lands. This applies to `git commit`, `git push`, tags, and branch creation.
- **Commit messages are a single line.** No body, no bullet list, no explanation
  paragraph, no trailers. If the change needs more explanation than one line, say it to me
  in chat instead of in the message.
- **Never add AI attribution to a commit.** No `Co-Authored-By: Claude`, no
  "Generated with Claude Code", no tool mention anywhere in the message. Commits are
  authored by me alone.

---

## Working agreement

- Build and check before reporting done: `./gradlew assembleDebug`. Read `logcat` for runtime
  failures instead of guessing.
- `LockPolicy` and `ForegroundPackageTracker` are pure/near-pure — unit test them. Everything
  else needs a device.
- **Permission flows, overlay timing, and OEM background-kill behavior cannot be verified in an
  emulator.** Flag these for me to test manually rather than claiming they work.
- Xiaomi, Oppo, Vivo, and Samsung each add their own autostart and battery-optimization screens.
  Behavior varies by skin, not Android version. Don't assume; ask me to check the device.
- Prefer editing existing files over adding new ones. Ask before introducing a new module or
  architectural layer.

## Do not

- Add React Native, or any JS/bridge layer.
- Put unlock-decision logic anywhere but Kotlin (it's readable in a release APK otherwise).
- Persist `UnlockSessionManager` state.
- Use `UsageStatsManager` polling as the detection mechanism — battery cost, and the system
  won't keep it alive the way it does an accessibility service.
- Call `finish()` on back press in `LockScreenActivity`.
- Commit model files or the enrollment embedding.
- Commit or push anything before I ask for it.
- Put a description body or AI attribution in a commit message.
