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
- **Minimum audio length is 0.05 s (800 samples).** Below that the exported graph throws
  from a reflect-pad inside the ECAPA conv blocks; it does not return a bad embedding.
  Check the captured length before calling inference. A spoken passphrase is far longer,
  so treat ~0.5 s as the practical floor and reject anything shorter as "too short".
- The exported graph takes **batch 1 only** and rejects anything else, so no silent
  garbage there. Inference is deterministic: identical input gives bitwise identical output.
- Verification latency is **not** on the ~100 ms lock-screen path. The lock screen is
  already up by the time the user speaks, so a few hundred ms of inference is fine.
  (3 s clip measured at 59 ms on desktop CPU; the phone will be several times slower.)
- PIN fallback is mandatory from the first audio milestone. Voice will produce false rejections.

### ONNX export traps (learned in M2)

Tooling lives in `tools/onnx/`: `export_ecapa.py` writes the graph, `verify_parity.py`
is the gate. Requires `onnx`, `onnxruntime`, `onnxscript`. Artifacts land in
`tools/onnx/build/` (~166 MB with checkpoints) and are gitignored.

Three SpeechBrain modules cannot be traced as-is. All three fail **silently** with
plausible-looking embeddings, which is why parity is measured, not assumed:

- **`torch.stft`** cannot be exported at all: TorchScript rejects complex types and
  `torch.export` has no meta kernel for `aten._fft_r2c`. Replaced by `ConvStft`, a
  real-valued DFT as a strided conv1d (~1e-7 relative). Only Conv/MatMul, so it does
  not depend on ONNX Runtime shipping an STFT kernel on Android.
- **Tracing on silence.** An all-zero dummy makes the fbank output constant, so the
  per-utterance mean folds into the graph and normalization stops depending on the
  input. Measured 0.03 cosine. Trace with real spectral content.
- **Frozen frame counts.** `InputNormalization` slices by `round(lengths * x.shape[1])`
  and the attentive-pooling mask uses `max_len=L`. Both become constants, so the model
  is right only at the traced duration — 0.88 cosine elsewhere. Replaced by
  `SentenceMeanNorm` and `FullLengthAttentiveStatisticsPooling`, exact for a single
  un-padded utterance, which is all the app submits.

- **`squeeze(dim)`** lowers to an ONNX `If` guarding against the dim not being 1, which
  leaves dynamic control flow at the output and makes the output shape symbolic. Index
  (`x[:, 0, :]`) instead. Fewer exotic ops is less risk on whatever ORT build ships.

**Always verify at several durations.** A frozen shape produces wrong numbers rather
than an error, so testing only the traced length proves nothing.

Result: cosine 1.00000000 vs the unmodified pipeline across 1.0-7.3 s, relative max
diff ~2e-6, speaker scores preserved to ~2e-7. Export is 80 MB float32, opset 17 —
relevant to M6.

---

### Language support — decided 2026-08-26

**Supported languages: Indonesian and English.** Two languages, so the user picks one; a
picker is warranted rather than an implicit default.

- The choice is made **before** the passphrase is typed and before voice enrollment, so the
  passphrase is entered and recorded under a known language. It belongs to the enrollment
  flow (M4), not to M1 settings.
- This does **not** affect the speaker-verification model. ECAPA-TDNN embeddings model the
  speaker, not the words, so M2 exports the same model regardless of language.
- It does affect **M5**: the keyword check must handle both languages, via either one
  multilingual keyword spotter or one model per language. Two models is the APK-size risk
  that M6 exists to address — prefer multilingual if accuracy allows.
- Persist the choice alongside the enrollment embedding. Changing language after enrolling
  invalidates the passphrase, so treat it as a re-enrollment, not a settings toggle.

---

## Roadmap

- [ ] **M1 — lock mechanism (current).** Detect foreground app, block it, hardcoded unlock button.
      No audio, no ML, no model files.
- [ ] M2 — ONNX export verified: embeddings match the Python original on the same wav.
- [ ] M3 — `AudioRecord` capture + on-device inference wired to the lock screen.
- [ ] M4 — enrollment flow (language choice -> passphrase entry -> voice enrollment),
      threshold calibration against my own FAR/FRR measurements.
- [ ] M5 — keyword/passphrase check, Indonesian and English.
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
