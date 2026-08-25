package com.prabu.voicelock.ui.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prabu.voicelock.service.AppWatchService

/**
 * Both of these permission flows have to be granted in system Settings; neither can be
 * requested with a runtime permission dialog.
 */
@Composable
fun OnboardingScreen(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Set up VoiceLock",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(24.dp))

        PermissionCard(
            title = "App watcher",
            explanation = "VoiceLock needs its accessibility service enabled to see which " +
                "app comes to the foreground. Only the package name is read.",
            granted = accessibilityEnabled,
            actionLabel = "Open accessibility settings",
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
        Spacer(Modifier.height(16.dp))

        PermissionCard(
            title = "Display over other apps",
            explanation = "Required so the lock screen can be launched while another app " +
                "is in the foreground. VoiceLock does not draw an overlay.",
            granted = overlayGranted,
            actionLabel = "Open overlay settings",
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    explanation: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (granted) "$title — granted" else title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = explanation, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClick) { Text(actionLabel) }
            }
        }
    }
}

/**
 * Reads the colon-separated list of enabled services from Settings.Secure. There is no
 * API to ask whether our own accessibility service is running.
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AppWatchService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        val service = splitter.next()
        if (service.equals(expected, ignoreCase = true)) return true
        // Some OEM builds store the short form of the component name.
        if (service.equals(expected.replace("${context.packageName}/${context.packageName}", "${context.packageName}/"), ignoreCase = true)) {
            return true
        }
    }
    return false
}

fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
