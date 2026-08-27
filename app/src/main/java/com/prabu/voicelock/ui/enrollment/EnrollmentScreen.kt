package com.prabu.voicelock.ui.enrollment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prabu.voicelock.data.prefs.PassphraseLanguage

@Composable
fun EnrollmentScreen(
    modifier: Modifier = Modifier,
    /** Non-null when re-enrolling, where finishing has to return somewhere. */
    onFinished: (() -> Unit)? = null,
    viewModel: EnrollmentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Enroll your voice", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        when (state.step) {
            EnrollmentViewModel.Step.LANGUAGE -> LanguageStep(viewModel::chooseLanguage)

            EnrollmentViewModel.Step.PASSPHRASE -> PassphraseStep(
                language = state.language,
                message = state.message,
                onSubmit = viewModel::submitPassphrase,
            )

            EnrollmentViewModel.Step.RECORDING -> RecordingStep(
                state = state,
                onRecord = viewModel::recordSample,
                onDiscard = viewModel::discardLastSample,
                onSave = { viewModel.save(force = state.inconsistent) },
            )

            EnrollmentViewModel.Step.DONE -> DoneStep(
                onRedo = viewModel::restart,
                onFinished = onFinished,
            )
        }
    }
}

@Composable
private fun LanguageStep(onChoose: (PassphraseLanguage) -> Unit) {
    Text(
        text = "Which language will you speak your passphrase in? This is chosen first " +
            "because the passphrase is typed and recorded under it, and changing it " +
            "later means enrolling again.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    PassphraseLanguage.entries.forEach { language ->
        Button(
            onClick = { onChoose(language) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(language.label)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PassphraseStep(
    language: PassphraseLanguage?,
    message: String?,
    onSubmit: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    Text(
        text = "Type the phrase you will say, in ${language?.label ?: "the chosen language"}. " +
            "Pick something you can repeat the same way every time.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("Passphrase") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onSubmit(passphrase) },
        enabled = passphrase.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
}

@Composable
private fun RecordingStep(
    state: EnrollmentViewModel.UiState,
    onRecord: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val required = EnrollmentViewModel.REQUIRED_SAMPLES
    val complete = state.capturedSamples >= required

    Text(
        text = "Say \"${state.passphrase}\" ${required} times, the same way each time. " +
            "Recordings are turned into a voiceprint and then discarded.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = "${state.capturedSamples} of $required recorded",
        style = MaterialTheme.typography.titleMedium,
    )

    if (state.consistency != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            // Shown during enrollment because it is the only signal that the samples
            // disagree, and the threshold behind it is not calibrated yet.
            text = "Similarity between recordings: %.2f".format(state.consistency),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    if (state.busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.message ?: "Working…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    } else {
        Button(
            onClick = onRecord,
            enabled = !complete,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (complete) "All recordings captured" else "Record (3s)")
        }
        if (state.message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.inconsistent) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (state.capturedSamples > 0) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDiscard) { Text("Discard last recording") }
        }
        if (complete) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.inconsistent) "Save anyway" else "Save voiceprint")
            }
        }
    }
}

@Composable
private fun DoneStep(onRedo: () -> Unit, onFinished: (() -> Unit)?) {
    Text("Voiceprint saved", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Locked apps will now ask you to speak. The verification threshold is " +
            "still a provisional guess, not calibrated against your own recordings, " +
            "so expect it to be wrong in both directions. The PIN always works.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onRedo, modifier = Modifier.fillMaxWidth()) {
        Text("Enroll again")
    }
    if (onFinished != null) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
