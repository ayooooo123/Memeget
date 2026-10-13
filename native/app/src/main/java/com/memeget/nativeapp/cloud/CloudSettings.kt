package com.memeget.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudSettingsPanel(assistant: CloudAssistant, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(CloudConfig()) }
    var draft by remember { mutableStateOf(CloudConfig()) }
    var key by remember { mutableStateOf("") }
    var captionKey by remember { mutableStateOf("") }
    var requestLimit by remember { mutableStateOf("20") }
    var byteLimitMiB by remember { mutableStateOf("10") }
    var costLimit by remember { mutableStateOf("0.05") }
    var busy by remember { mutableStateOf(false) }
    var ledger by remember { mutableStateOf<LedgerSnapshot?>(null) }
    val progress by assistant.progress.collectAsState()

    LaunchedEffect(assistant) {
        try {
            saved = assistant.config()
            draft = saved
            requestLimit = saved.dailyRequestLimit.toString()
            byteLimitMiB = (saved.dailyByteLimit / (1024 * 1024)).toString()
            costLimit = saved.dailyCostBudgetUsd.toString()
            ledger = assistant.getLedgerSnapshot()
        } catch (failure: Exception) { onError(failure.message ?: "Cloud settings could not be read") }
    }
    LaunchedEffect(progress) {
        try { ledger = assistant.getLedgerSnapshot() }
        catch (failure: Exception) { onError(failure.message ?: "Cloud budget could not be read") }
    }

    fun save(enabled: Boolean) {
        scope.launch {
            busy = true
            try {
                val config = draft.copy(
                    enabled = enabled,
                    dailyRequestLimit = requestLimit.toIntOrNull() ?: error("Enter a whole-number request limit"),
                    dailyByteLimit = (byteLimitMiB.toLongOrNull() ?: error("Enter a whole-number MiB limit")) * 1024 * 1024,
                    dailyCostBudgetUsd = costLimit.toDoubleOrNull() ?: error("Enter an estimated dollar budget"),
                )
                assistant.saveConfig(config, key.takeIf(String::isNotBlank), captionKey.takeIf(String::isNotBlank))
                saved = assistant.config()
                draft = saved
                key = ""
                captionKey = ""
                ledger = assistant.getLedgerSnapshot()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                saved = assistant.config()
                onError(failure.message ?: "Cloud configuration was not saved")
            }
            finally { busy = false }
        }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Optional cloud AI · ${if (saved.enabled) "ON" else "OFF"}", style = MaterialTheme.typography.titleLarge)
            Text("Cloud OFF sends no analysis data. Model downloads and links you explicitly import can still use the network. Previously saved cloud descriptions and template hints remain usable offline.", style = MaterialTheme.typography.bodySmall)
            if (saved.enabled) {
                Button(enabled = !busy, onClick = {
                    assistant.cancel()
                    scope.launch {
                        try {
                            assistant.saveConfig(saved.copy(enabled = false), null, null)
                            saved = assistant.config(); draft = saved
                        } catch (failure: Exception) { onError(failure.message ?: "Could not persist cloud OFF") }
                    }
                }) { Text("Turn cloud OFF now") }
            }
            Text("Decision provider", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(CloudProviderType.TYPESAFE_SYSTEMONE to "JEV", CloudProviderType.CLOUDFLARE_CLEF to "Clef", CloudProviderType.SELFHOST_SYSTEMONE to "Self-host").forEach { (provider, label) ->
                    FilterChip(selected = draft.provider == provider, onClick = {
                        draft = draft.copy(provider = provider); key = ""
                    }, label = { Text(label) })
                }
            }
            when (draft.provider) {
                CloudProviderType.TYPESAFE_SYSTEMONE -> {
                    Text("JEV judges supplied text/state, not pixels. Typed outputs can still be wrong.")
                    OutlinedTextField(draft.typesafeModel, { draft = draft.copy(typesafeModel = it) }, label = { Text("JEV model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                CloudProviderType.CLOUDFLARE_CLEF -> {
                    Text("Clef can judge selected image frames and evidence. Its 9B/27B weights run at the provider, not on this device.")
                    OutlinedTextField(draft.cloudflareAccountId, { draft = draft.copy(cloudflareAccountId = it.trim()) }, label = { Text("Cloudflare account ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("clef-flash", "clef").forEach { model -> FilterChip(draft.cloudflareModel == model, { draft = draft.copy(cloudflareModel = model) }, label = { Text(model) }) }
                    }
                }
                CloudProviderType.SELFHOST_SYSTEMONE -> {
                    Text("Use an HTTPS System One endpoint, including a compatible Clef or Laya server on another computer.")
                    OutlinedTextField(draft.selfhostUrl, { draft = draft.copy(selfhostUrl = it.trim()) }, label = { Text("Full HTTPS /v1/systemone URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(draft.typesafeModel, { draft = draft.copy(typesafeModel = it) }, label = { Text("Server model name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
            OutlinedTextField(key, { key = it }, label = { Text("Decision API key") }, supportingText = { Text("Leave blank to keep the key for the unchanged endpoint. Credentials are encrypted with Android Keystore.") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            HorizontalDivider()
            Text("What may leave this device?", style = MaterialTheme.typography.titleMedium)
            Text("Text and pictures may reveal usernames, messages, faces or locations. Removing EXIF metadata does not anonymize a meme. Disabling cloud stops future uploads; it cannot recall bytes already sent.", style = MaterialTheme.typography.bodySmall)
            Text("Decision requests include bundled candidate names. Sending locally taught names also requires text permission. Paths, file names and embedding vectors are never uploaded.", style = MaterialTheme.typography.bodySmall)
            CloudSwitch("Allow OCR and local-caption text", draft.consentTextState) { draft = draft.copy(consentTextState = it) }
            CloudSwitch("Allow up to two resized, metadata-free image frames", draft.consentVisualMedia) { draft = draft.copy(consentVisualMedia = it) }
            CloudSwitch("Also allow transcripts in text evidence", draft.consentTranscript) { draft = draft.copy(consentTranscript = it) }
            CloudSwitch("Unmetered networks only", draft.unmeteredOnly) { draft = draft.copy(unmeteredOnly = it) }
            CloudSwitch("Automatically escalate uncertain NEW items", draft.automaticEscalation) { draft = draft.copy(automaticEscalation = it) }
            Text("Existing items are excluded at each new opt-in unless you explicitly include them below. Provider confidence is not measured identity accuracy.", style = MaterialTheme.typography.bodySmall)
            CloudSwitch("Also authorize analysis of the existing library", draft.includeExisting) { draft = draft.copy(includeExisting = it) }
            HorizontalDivider()
            CloudSwitch("Optional open-vocabulary caption endpoint", draft.captionEndpointEnabled) { draft = draft.copy(captionEndpointEnabled = it) }
            if (draft.captionEndpointEnabled) {
                Text("System One selects choices; it does not write captions. This separate endpoint receives an image only when visual consent is enabled. It uses its own API key and a separate budget reservation.")
                OutlinedTextField(draft.captionEndpointUrl, { draft = draft.copy(captionEndpointUrl = it.trim()) }, label = { Text("HTTPS chat-completions endpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(draft.captionModel, { draft = draft.copy(captionModel = it) }, label = { Text("Caption model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(captionKey, { captionKey = it }, label = { Text("Separate caption API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (draft.provider == CloudProviderType.SELFHOST_SYSTEMONE || draft.captionEndpointEnabled) {
                CloudSwitch("I have set spending limits at unpriced providers", draft.acknowledgeProviderBudget) { draft = draft.copy(acknowledgeProviderBudget = it) }
                Text("Custom and caption endpoints are NOT included in the dollar estimate. Request and traffic caps still apply. Do not enable a paid endpoint without its own account spending cap.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(requestLimit, { requestLimit = it }, label = { Text("Daily request limit (1–500)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(byteLimitMiB, { byteLimitMiB = it }, label = { Text("Daily traffic reservations, MiB (1–100)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(costLimit, { costLimit = it }, label = { Text("Known-provider estimated budget, USD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Each request reserves its complete UTF-8 body plus the 64 KiB maximum response before sending. Maximum request: 1 MiB. No automatic retries. Timeouts remain charged. Dollar reservations use the published hosted context ceiling, not a hard guarantee of your bill.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { save(false) }) { Text("Save with cloud OFF") }
                Button(enabled = !busy, onClick = { save(true) }) { Text("Enable selected scope") }
            }
            ledger?.let { state ->
                Text("Reserved: ${state.requestsCount}/${state.maxRequests} requests · ${state.bytesCount / 1024} KiB · known-provider estimate $${"%.4f".format(java.util.Locale.ROOT, state.costEstimateUsd)}", style = MaterialTheme.typography.bodySmall)
            }
            if (saved.enabled && saved.includeExisting) {
                OutlinedButton(onClick = { scope.launch { assistant.analyzeExisting() } }, enabled = !progress.running) { Text("Analyze existing items within limits") }
            }
            if (progress.running) Text(progress.detail)
            progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = {
                scope.launch {
                    try { assistant.forget() }
                    catch (failure: Exception) { onError(failure.message ?: "Could not remove cloud observations") }
                }
            }) { Text("Remove cached cloud observations") }
            Text("User-confirmed teachings, rejection records and spending reservations are preserved. Unconfirmed cloud results never become automatic training positives.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CloudSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
