package com.prabu.voicelock.ui.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prabu.voicelock.data.prefs.PinStore
import com.prabu.voicelock.service.AppWatchService

/**
 * Setup gate. The two system permissions can only be granted in Settings, the
 * microphone is a runtime permission, and the PIN is required before locking is
 * useful: voice will produce false rejections, so there has to be a way in.
 */
@Composable
fun OnboardingScreen(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    microphoneGranted: Boolean,
    onRequestMicrophone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val pinSet by viewModel.isPinSet.collectAsStateWithLifecycle()
    val pinError by viewModel.pinError.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Set up VoiceLock", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        SetupCard(
            title = "App watcher",
            explanation = "VoiceLock needs its accessibility service enabled to see which " +
                "app comes to the foreground. Only the package name is read.",
            done = accessibilityEnabled,
            actionLabel = "Open accessibility settings",
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
        Spacer(Modifier.height(16.dp))

        SetupCard(
            title = "Display over other apps",
            explanation = "Required so the lock screen can be launched while another app " +
                "is in the foreground. VoiceLock does not draw an overlay.",
            done = overlayGranted,
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
        Spacer(Modifier.height(16.dp))

        SetupCard(
            title = "Microphone",
            explanation = "Needed to hear the passphrase. Audio is processed on the device " +
                "and never recorded to storage or uploaded.",
            done = microphoneGranted,
            actionLabel = "Grant microphone access",
            onClick = onRequestMicrophone,
        )
        Spacer(Modifier.height(16.dp))

        PinCard(pinSet = pinSet, error = pinError, onSubmit = viewModel::setPin)
    }
}

@Composable
private fun PinCard(
    pinSet: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (pinSet) "Fallback PIN — set" else "Fallback PIN",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Voice recognition will sometimes reject you. The PIN is how you " +
                    "get in when it does. Stored as a salted hash, never as the PIN.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!pinSet) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
                    label = { Text("PIN (min ${PinStore.MIN_PIN_LENGTH} digits)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it.filter(Char::isDigit).take(MAX_PIN_LENGTH)
                        mismatch = false
                    },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                val message = when {
                    mismatch -> "PINs do not match"
                    error != null -> error
                    else -> null
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (pin != confirmation) {
                                mismatch = true
                            } else {
                                onSubmit(pin)
                                pin = ""
                                confirmation = ""
                            }
                        },
                        enabled = pin.isNotEmpty() && confirmation.isNotEmpty(),
                    ) {
                        Text("Set PIN")
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    title: String,
    explanation: String,
    done: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (done) "$title — granted" else title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = explanation, style = MaterialTheme.typography.bodyMedium)
            if (!done) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClick) { Text(actionLabel) }
            }
        }
    }
}

/**
 * Reads the colon-separated list of enabled services from Settings.Secure. There is
 * no API to ask whether our own accessibility service is running.
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
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}

fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

private const val MAX_PIN_LENGTH = 12
