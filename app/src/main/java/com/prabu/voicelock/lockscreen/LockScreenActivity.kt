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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.prabu.voicelock.ui.theme.VoiceLockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Full-screen Activity rather than a WindowManager overlay: Compose needs a
 * LifecycleOwner and a SavedStateRegistryOwner, which a Service-hosted ComposeView does
 * not have, and back-button and focus handling are standard on an Activity.
 *
 * Declared singleInstance with taskAffinity="" and excludeFromRecents, so it lives in
 * its own task and the user cannot swipe or recents their way back into the locked app.
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
                    // Finishing here is correct: the grant is already recorded, so
                    // returning to the app will not re-trigger the lock.
                    finish()
                }
            }
        }

        setContent {
            VoiceLockTheme {
                LockScreen(
                    lockedPackage = lockedPackage,
                    onUnlock = { viewModel.unlock(lockedPackage) },
                )
            }
        }
    }

    /**
     * singleInstance means a lock request for a different app is delivered here instead
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
                // NEW_TASK is required to start an Activity from a Service. NO_ANIMATION
                // removes the window transition, which is faster and avoids animating
                // over the locked app content.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

@Composable
private fun LockScreen(
    lockedPackage: String,
    onUnlock: () -> Unit,
) {
    val context = LocalContext.current
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
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = appLabel,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Locked by VoiceLock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            // M1: the voice challenge is not built yet, so this button is the unlock.
            Button(onClick = onUnlock) {
                Text("Unlock")
            }
        }
    }
}
