package com.prabu.voicelock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prabu.voicelock.ui.applist.AppListScreen
import com.prabu.voicelock.ui.onboarding.OnboardingScreen
import com.prabu.voicelock.ui.onboarding.canDrawOverlays
import com.prabu.voicelock.ui.onboarding.isAccessibilityServiceEnabled
import com.prabu.voicelock.ui.theme.VoiceLockTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceLockTheme {
                Surface(Modifier.fillMaxSize()) {
                    VoiceLockRoot()
                }
            }
        }
    }
}

@Composable
private fun VoiceLockRoot() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val accessibilityEnabled = remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    val overlayGranted = remember { mutableStateOf(canDrawOverlays(context)) }

    // Both permissions are granted in system Settings, so the only way to notice a
    // change is to re-read them when this screen comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled.value = isAccessibilityServiceEnabled(context)
                overlayGranted.value = canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (accessibilityEnabled.value && overlayGranted.value) {
        AppListScreen()
    } else {
        OnboardingScreen(
            accessibilityEnabled = accessibilityEnabled.value,
            overlayGranted = overlayGranted.value,
        )
    }
}
