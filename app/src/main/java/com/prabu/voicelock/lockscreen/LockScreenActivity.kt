package com.prabu.voicelock.lockscreen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.prabu.voicelock.BuildConfig
import com.prabu.voicelock.domain.VoiceMatch
import com.prabu.voicelock.ui.theme.VoiceLockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Full-screen Activity rather than a WindowManager overlay: Compose needs a
 * LifecycleOwner and a SavedStateRegistryOwner, which a Service-hosted ComposeView
 * does not have, and back-button and focus handling are standard on an Activity.
 *
 * Declared singleInstance with taskAffinity="" and excludeFromRecents, so it lives
 * in its own task and the user cannot swipe or recents their way back in.
 */
@AndroidEntryPoint
class LockScreenActivity : ComponentActivity() {

    private val viewModel: LockScreenViewModel by viewModels()

    private var lockedPackage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Blocks screenshots and keeps the lock screen out of recents thumbnails.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        lockedPackage = intent.lockedPackageExtra().orEmpty()
        if (lockedPackage.isEmpty()) {
            // Nothing to guard; do not sit in front of the user.
            goHome()
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome()
            },
        )

        lifecycleScope.launch {
            viewModel.unlocked.collect { unlockedPackage ->
                if (unlockedPackage == lockedPackage) {
                    // Finishing is correct here: the grant is already recorded, so
                    // returning to the app will not re-trigger the lock.
                    finish()
                }
            }
        }

        setContent {
            VoiceLockTheme {
                LockScreen(
                    lockedPackage = lockedPackage,
                    viewModel = viewModel,
                )
            }
        }
    }

    /**
     * singleInstance means a lock request for a different app arrives here instead
     * of creating a second instance, so the target has to be re-read.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.lockedPackageExtra()?.takeIf { it.isNotEmpty() }?.let { lockedPackage = it }
    }

    /**
     * Back goes to the launcher and deliberately does not finish(). Finishing would
     * return the user straight into the locked app.
     */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun Intent.lockedPackageExtra(): String? = getStringExtra(EXTRA_LOCKED_PACKAGE)

    companion object {
        private const val EXTRA_LOCKED_PACKAGE = "com.prabu.voicelock.extra.LOCKED_PACKAGE"

        fun newIntent(context: Context, lockedPackage: String): Intent =
            Intent(context, LockScreenActivity::class.java)
                .putExtra(EXTRA_LOCKED_PACKAGE, lockedPackage)
                // NEW_TASK is required to start an Activity from a Service.
                // NO_ANIMATION removes the window transition, which is faster and
                // avoids animating over the locked app content.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

@Composable
private fun LockScreen(
    lockedPackage: String,
    viewModel: LockScreenViewModel,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    val appLabel = remember(lockedPackage) {
        runCatching {
            val packageManager = context.packageManager
            val info = packageManager.getApplicationInfo(lockedPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(lockedPackage)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = appLabel,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Locked by VoiceLock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.passphraseHint != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Say: \"${state.passphraseHint}\"",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
            VoiceSection(
                voice = state.voice,
                onRecord = { viewModel.captureVoice(lockedPackage) },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(text = "Enter PIN to unlock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
                label = { Text("PIN") },
                singleLine = true,
                enabled = state.pinEntryEnabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.pinMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.pinMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    viewModel.submitPin(lockedPackage, pin)
                    pin = ""
                },
                enabled = state.pinEntryEnabled && pin.isNotEmpty(),
            ) {
                Text("Unlock")
            }
        }
    }
}

@Composable
private fun VoiceSection(
    voice: LockScreenViewModel.VoiceState,
    onRecord: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (voice) {
            is LockScreenViewModel.VoiceState.Recording -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Listening…", style = MaterialTheme.typography.bodyMedium)
            }

            is LockScreenViewModel.VoiceState.Computing -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Verifying…", style = MaterialTheme.typography.bodyMedium)
            }

            else -> Button(onClick = onRecord) { Text("Speak passphrase (3s)") }
        }

        val detail: Pair<String, Boolean>? = when (voice) {
            is LockScreenViewModel.VoiceState.Accepted ->
                // The score is shown only in debug builds: it is exactly the feedback
                // an impostor would use to tune an attack, but it is also what makes
                // threshold calibration possible.
                (if (BuildConfig.DEBUG) {
                    "Recognized (%.3f, %d ms)".format(voice.similarity, voice.inferenceMillis)
                } else {
                    "Recognized"
                }) to false

            is LockScreenViewModel.VoiceState.Rejected ->
                (if (BuildConfig.DEBUG) {
                    "Not recognized (%.3f, threshold %.2f, %d ms)".format(
                        voice.similarity,
                        VoiceMatch.PROVISIONAL_THRESHOLD,
                        voice.inferenceMillis,
                    )
                } else {
                    "Not recognized. Try again or use the PIN."
                }) to true

            is LockScreenViewModel.VoiceState.Failed -> voice.message to true

            else -> null
        }

        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail.first,
                style = MaterialTheme.typography.bodySmall,
                color = if (detail.second) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val MAX_PIN_LENGTH = 12
