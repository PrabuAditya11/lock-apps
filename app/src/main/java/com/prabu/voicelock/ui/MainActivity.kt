package com.prabu.voicelock.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prabu.voicelock.ui.applist.AppListScreen
import com.prabu.voicelock.ui.enrollment.EnrollmentScreen
import com.prabu.voicelock.ui.onboarding.OnboardingScreen
import com.prabu.voicelock.ui.onboarding.OnboardingViewModel
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
private fun VoiceLockRoot(viewModel: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val accessibilityEnabled = remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    val overlayGranted = remember { mutableStateOf(canDrawOverlays(context)) }
    val microphoneGranted = remember { mutableStateOf(hasMicrophonePermission(context)) }
    val pinSet by viewModel.isPinSet.collectAsStateWithLifecycle()
    val enrolled by viewModel.isEnrolled.collectAsStateWithLifecycle()
    var reEnrolling by remember { mutableStateOf(false) }

    val requestMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> microphoneGranted.value = granted }

    // The two Settings permissions can change outside the app, so they are re-read
    // whenever this screen returns to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled.value = isAccessibilityServiceEnabled(context)
                overlayGranted.value = canDrawOverlays(context)
                microphoneGranted.value = hasMicrophonePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionsAndPinDone = accessibilityEnabled.value &&
        overlayGranted.value &&
        microphoneGranted.value &&
        pinSet

    if (permissionsAndPinDone && (enrolled || reEnrolling)) {
        if (reEnrolling) {
            EnrollmentScreen(onFinished = { reEnrolling = false })
        } else {
            AppListScreen(onReEnroll = { reEnrolling = true })
        }
    } else if (permissionsAndPinDone) {
        // Permissions and PIN are done but there is no voiceprint yet.
        EnrollmentScreen()
    } else {
        OnboardingScreen(
            accessibilityEnabled = accessibilityEnabled.value,
            overlayGranted = overlayGranted.value,
            microphoneGranted = microphoneGranted.value,
            onRequestMicrophone = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
        )
    }
}

private fun hasMicrophonePermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
