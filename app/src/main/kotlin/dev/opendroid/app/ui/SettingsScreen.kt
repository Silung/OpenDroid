package dev.opendroid.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.opendroid.app.BuildConfig
import dev.opendroid.app.overlay.FloatingOverlayService
import dev.opendroid.app.LlmApiFormat
import dev.opendroid.app.OpenDroidSettings
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.opendroid.app.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember(ctx) { OpenDroidSettings(ctx) }
    var apiKey by remember { mutableStateOf(settings.anthropicApiKeyStoredRaw()) }
    var baseUrl by remember { mutableStateOf(settings.anthropicBaseUrlStoredRaw()) }
    var model by remember { mutableStateOf(settings.modelStoredRaw()) }
    var maxTurns by remember { mutableStateOf(settings.maxTurns.toString()) }
    var maxLlmHistory by remember { mutableStateOf(settings.maxLlmHistoryAssistantMessagesStoredRaw().ifBlank { "6" }) }
    var overlayEnabled by remember { mutableStateOf(settings.overlayEnabled) }
    var apiFormat by remember { mutableStateOf(settings.llmApiFormat) }

    LifecycleResumeEffect(Unit) {
        overlayEnabled = settings.overlayEnabled
        onPauseOrDispose { }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun requestOverlayFlow() {
        settings.overlayEnabled = true
        overlayEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!ok) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (!Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    Button(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Text(stringResource(R.string.label_api_format), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val formatOptions =
                listOf(LlmApiFormat.OPENAI_CHAT_COMPLETIONS, LlmApiFormat.ANTHROPIC_MESSAGES)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                formatOptions.forEachIndexed { index, opt ->
                    SegmentedButton(
                        selected = apiFormat == opt,
                        onClick = { apiFormat = opt },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = formatOptions.size),
                    ) {
                        Text(
                            when (opt) {
                                LlmApiFormat.OPENAI_CHAT_COMPLETIONS ->
                                    stringResource(R.string.api_format_openai_short)
                                LlmApiFormat.ANTHROPIC_MESSAGES ->
                                    stringResource(R.string.api_format_anthropic_short)
                            },
                        )
                    }
                }
            }
            Text(
                when (apiFormat) {
                    LlmApiFormat.OPENAI_CHAT_COMPLETIONS ->
                        stringResource(R.string.api_format_openai_desc)
                    LlmApiFormat.ANTHROPIC_MESSAGES ->
                        stringResource(R.string.api_format_anthropic_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.label_api_key)) },
                placeholder = { Text(stringResource(R.string.hint_api_key_default)) },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.label_base_url)) },
                placeholder = { Text(BuildConfig.DEFAULT_LLM_BASE_URL) },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.label_model)) },
                placeholder = { Text(BuildConfig.DEFAULT_LLM_MODEL) },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = maxTurns,
                onValueChange = { maxTurns = it },
                label = { Text(stringResource(R.string.label_max_agent_turns)) },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = maxLlmHistory,
                onValueChange = { maxLlmHistory = it },
                label = { Text(stringResource(R.string.label_max_llm_history_messages)) },
                placeholder = { Text("6") },
                supportingText = { Text(stringResource(R.string.support_max_llm_history_messages)) },
                singleLine = true,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                ) {
                    Text(stringResource(R.string.overlay_output_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.overlay_output_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = overlayEnabled,
                    onCheckedChange = { on ->
                        if (on) {
                            requestOverlayFlow()
                        } else {
                            settings.overlayEnabled = false
                            overlayEnabled = false
                            FloatingOverlayService.stop(ctx)
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    settings.anthropicApiKey = apiKey.trim()
                    settings.anthropicBaseUrl = baseUrl.trim()
                    settings.model = model.trim()
                    settings.llmApiFormat = apiFormat
                    settings.maxTurns = maxTurns.toIntOrNull() ?: 24
                    settings.maxLlmHistoryAssistantMessages = maxLlmHistory.toIntOrNull()?.coerceIn(1, 256) ?: 6
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save_and_back))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_footer_tips),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}
