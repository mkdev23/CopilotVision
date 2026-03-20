package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp.CvpCaptureMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Azure CVP
    var azureSpeechRegion by remember { mutableStateOf(SettingsManager.azureSpeechRegion) }
    var azureSpeechKey by remember { mutableStateOf(SettingsManager.azureSpeechKey) }
    var azureOpenAIEndpoint by remember { mutableStateOf(SettingsManager.azureOpenAIEndpoint) }
    var azureOpenAIKey by remember { mutableStateOf(SettingsManager.azureOpenAIKey) }
    var azureOpenAIDeployment by remember { mutableStateOf(SettingsManager.azureOpenAIDeployment) }
    var cvpGatewayUrl by remember { mutableStateOf(SettingsManager.cvpGatewayUrl) }
    var cvpGatewayToken by remember { mutableStateOf(SettingsManager.cvpGatewayToken) }
    var azureVisionEndpoint by remember { mutableStateOf(SettingsManager.azureVisionEndpoint) }
    var azureVisionKey by remember { mutableStateOf(SettingsManager.azureVisionKey) }

    var microsoftGraphToken by remember { mutableStateOf(SettingsManager.microsoftGraphToken) }
    var teamsDefaultChatId by remember { mutableStateOf(SettingsManager.teamsDefaultChatId) }
    var cvpFoundryProjectEndpoint by remember { mutableStateOf(SettingsManager.cvpFoundryProjectEndpoint) }
    var cvpFoundryAgentId by remember { mutableStateOf(SettingsManager.cvpFoundryAgentId) }
    var cvpMcpServerUrl by remember { mutableStateOf(SettingsManager.cvpMcpServerUrl) }
    var cvpMcpBearerToken by remember { mutableStateOf(SettingsManager.cvpMcpBearerToken) }
    var geminiAPIKey by remember { mutableStateOf(SettingsManager.geminiAPIKey) }
    var systemPrompt by remember { mutableStateOf(SettingsManager.geminiSystemPrompt) }
    var webrtcSignalingURL by remember { mutableStateOf(SettingsManager.webrtcSignalingURL) }
    var videoStreamingEnabled by remember { mutableStateOf(SettingsManager.videoStreamingEnabled) }
    var proactiveNotificationsEnabled by remember { mutableStateOf(SettingsManager.proactiveNotificationsEnabled) }
    var focusCoachingEnabled by remember { mutableStateOf(SettingsManager.focusCoachingEnabled) }
    var focusCoachingInterval by remember { mutableStateOf(SettingsManager.focusCoachingIntervalSeconds.toString()) }
    var workModeEnabled by remember { mutableStateOf(SettingsManager.workModeEnabled) }
    var cvpCaptureMode by remember { mutableStateOf(SettingsManager.cvpCaptureMode) }
    var showResetDialog by remember { mutableStateOf(false) }

    fun save() {
        SettingsManager.azureSpeechRegion = azureSpeechRegion.trim()
        SettingsManager.azureSpeechKey = azureSpeechKey.trim()
        SettingsManager.azureOpenAIEndpoint = azureOpenAIEndpoint.trim()
        SettingsManager.azureOpenAIKey = azureOpenAIKey.trim()
        SettingsManager.azureOpenAIDeployment = azureOpenAIDeployment.trim()
        SettingsManager.cvpGatewayUrl = cvpGatewayUrl.trim()
        SettingsManager.cvpGatewayToken = cvpGatewayToken.trim()
        SettingsManager.azureVisionEndpoint = azureVisionEndpoint.trim()
        SettingsManager.azureVisionKey = azureVisionKey.trim()
        SettingsManager.microsoftGraphToken = microsoftGraphToken.trim()
        SettingsManager.teamsDefaultChatId = teamsDefaultChatId.trim()
        SettingsManager.cvpFoundryProjectEndpoint = cvpFoundryProjectEndpoint.trim()
        SettingsManager.cvpFoundryAgentId = cvpFoundryAgentId.trim()
        SettingsManager.cvpMcpServerUrl = cvpMcpServerUrl.trim()
        SettingsManager.cvpMcpBearerToken = cvpMcpBearerToken.trim()
        SettingsManager.geminiAPIKey = geminiAPIKey.trim()
        SettingsManager.geminiSystemPrompt = systemPrompt.trim()
        SettingsManager.webrtcSignalingURL = webrtcSignalingURL.trim()
        SettingsManager.videoStreamingEnabled = videoStreamingEnabled
        SettingsManager.proactiveNotificationsEnabled = proactiveNotificationsEnabled
        SettingsManager.focusCoachingEnabled = focusCoachingEnabled
        focusCoachingInterval.trim().toIntOrNull()?.let { SettingsManager.focusCoachingIntervalSeconds = it }
        SettingsManager.workModeEnabled = workModeEnabled
        SettingsManager.cvpCaptureMode = cvpCaptureMode
    }

    fun reload() {
        azureSpeechRegion = SettingsManager.azureSpeechRegion
        azureSpeechKey = SettingsManager.azureSpeechKey
        azureOpenAIEndpoint = SettingsManager.azureOpenAIEndpoint
        azureOpenAIKey = SettingsManager.azureOpenAIKey
        azureOpenAIDeployment = SettingsManager.azureOpenAIDeployment
        cvpGatewayUrl        = SettingsManager.cvpGatewayUrl
        cvpGatewayToken      = SettingsManager.cvpGatewayToken
        azureVisionEndpoint = SettingsManager.azureVisionEndpoint
        azureVisionKey      = SettingsManager.azureVisionKey
        microsoftGraphToken = SettingsManager.microsoftGraphToken
        teamsDefaultChatId = SettingsManager.teamsDefaultChatId
        cvpFoundryProjectEndpoint = SettingsManager.cvpFoundryProjectEndpoint
        cvpFoundryAgentId = SettingsManager.cvpFoundryAgentId
        cvpMcpServerUrl = SettingsManager.cvpMcpServerUrl
        cvpMcpBearerToken = SettingsManager.cvpMcpBearerToken
        geminiAPIKey = SettingsManager.geminiAPIKey
        systemPrompt = SettingsManager.geminiSystemPrompt
        webrtcSignalingURL = SettingsManager.webrtcSignalingURL
        videoStreamingEnabled = SettingsManager.videoStreamingEnabled
        proactiveNotificationsEnabled = SettingsManager.proactiveNotificationsEnabled
        focusCoachingEnabled = SettingsManager.focusCoachingEnabled
        focusCoachingInterval = SettingsManager.focusCoachingIntervalSeconds.toString()
        workModeEnabled = SettingsManager.workModeEnabled
        cvpCaptureMode = SettingsManager.cvpCaptureMode
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = {
                    save()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Microsoft 365 ─────────────────────────────────────────────────
            SectionHeader("Microsoft 365 (Copilot Outputs)")
            MonoTextField(
                value = microsoftGraphToken,
                onValueChange = { microsoftGraphToken = it },
                label = "Graph Access Token",
                placeholder = "Get from graph.microsoft.com/graph-explorer",
                keyboardType = KeyboardType.Password,
            )
            MonoTextField(
                value = teamsDefaultChatId,
                onValueChange = { teamsDefaultChatId = it },
                label = "Teams Chat ID (optional)",
                placeholder = "19:xxxxx@thread.v2",
            )

            // ── Azure CVP ────────────────────────────────────────────────────
            SectionHeader("Azure Speech (Voice Live API)")
            MonoTextField(
                value = azureSpeechRegion,
                onValueChange = { azureSpeechRegion = it },
                label = "Region",
                placeholder = "eastus",
            )
            MonoTextField(
                value = azureSpeechKey,
                onValueChange = { azureSpeechKey = it },
                label = "Speech Key",
                placeholder = "Paste key from cvpspeech resource",
                keyboardType = KeyboardType.Password,
            )

            SectionHeader("Azure OpenAI (Foundry Agent)")
            MonoTextField(
                value = azureOpenAIEndpoint,
                onValueChange = { azureOpenAIEndpoint = it },
                label = "Endpoint",
                placeholder = "https://Cvp-Websocket.openai.azure.com/",
                keyboardType = KeyboardType.Uri,
            )
            MonoTextField(
                value = azureOpenAIKey,
                onValueChange = { azureOpenAIKey = it },
                label = "API Key",
                placeholder = "Paste key from Cvp-Websocket resource",
                keyboardType = KeyboardType.Password,
            )
            MonoTextField(
                value = azureOpenAIDeployment,
                onValueChange = { azureOpenAIDeployment = it },
                label = "Deployment",
                placeholder = "gpt-4o",
            )

            // Azure AI Foundry MCP routing (optional — falls back to Chat Completions if blank)
            SectionHeader("Azure AI Foundry (MCP Tool Routing)")
            MonoTextField(
                value = cvpFoundryProjectEndpoint,
                onValueChange = { cvpFoundryProjectEndpoint = it },
                label = "Foundry Project Endpoint",
                placeholder = "https://cvp.eastus2.inference.ml.azure.com",
                keyboardType = KeyboardType.Uri,
            )
            MonoTextField(
                value = cvpFoundryAgentId,
                onValueChange = { cvpFoundryAgentId = it },
                label = "Agent ID (from agent_service/agent.py)",
                placeholder = "asst_xxxxxxxxxxxx",
            )
            MonoTextField(
                value = cvpMcpServerUrl,
                onValueChange = { cvpMcpServerUrl = it },
                label = "MCP Server URL",
                placeholder = "https://cvp-mcp-server.<env>.azurecontainerapps.io/mcp",
                keyboardType = KeyboardType.Uri,
            )
            MonoTextField(
                value = cvpMcpBearerToken,
                onValueChange = { cvpMcpBearerToken = it },
                label = "MCP Bearer Token",
                keyboardType = KeyboardType.Password,
                placeholder = "Matches MCP_BEARER_TOKEN on the server",
            )

            SectionHeader("Azure Computer Vision")
            MonoTextField(
                value = azureVisionEndpoint,
                onValueChange = { azureVisionEndpoint = it },
                label = "Endpoint",
                placeholder = "https://cvp-demo.cognitiveservices.azure.com/",
                keyboardType = KeyboardType.Uri,
            )
            MonoTextField(
                value = azureVisionKey,
                onValueChange = { azureVisionKey = it },
                label = "API Key",
                keyboardType = KeyboardType.Password,
                placeholder = "Key from cvp-demo resource",
            )

            SectionHeader("System Prompt")
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            // WebRTC section
            SectionHeader("WebRTC")
            MonoTextField(
                value = webrtcSignalingURL,
                onValueChange = { webrtcSignalingURL = it },
                label = "Signaling URL",
                placeholder = "wss://your-server.example.com",
                keyboardType = KeyboardType.Uri,
            )

            // Video
            SectionHeader("Video")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Video Streaming", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Disable to save battery. Audio remains active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = videoStreamingEnabled,
                    onCheckedChange = { videoStreamingEnabled = it },
                )
            }

            // Notifications
            SectionHeader("Notifications")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Proactive Notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Receive proactive Foundry agent updates spoken through glasses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = proactiveNotificationsEnabled,
                    onCheckedChange = { proactiveNotificationsEnabled = it },
                )
            }

            // Focus Coaching
            SectionHeader("Focus Coaching")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("ADHD Focus Coach", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Periodic check-ins. Speaks up if you drift from your work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = focusCoachingEnabled,
                    onCheckedChange = { focusCoachingEnabled = it },
                )
            }
            if (focusCoachingEnabled) {
                MonoTextField(
                    value = focusCoachingInterval,
                    onValueChange = { focusCoachingInterval = it },
                    label = "Check-in interval (seconds)",
                    placeholder = "60",
                    keyboardType = KeyboardType.Number,
                )
            }

            // Work Mode
            SectionHeader("Vision (Work Mode)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Work Mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Enable burst capture via Azure Vision Gateway. Tap the eye button during streaming to capture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = workModeEnabled,
                    onCheckedChange = { workModeEnabled = it },
                )
            }
            if (workModeEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CvpCaptureMode.values().forEach { mode ->
                        val selected = cvpCaptureMode == mode
                        TextButton(onClick = { cvpCaptureMode = mode }) {
                            Text(
                                text = mode.name,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = if (selected) MaterialTheme.typography.labelLarge
                                        else MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            // Reset
            TextButton(onClick = { showResetDialog = true }) {
                Text("Reset to Defaults", color = Color.Red)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Settings") },
            text = { Text("This will reset all settings to the values built into the app.") },
            confirmButton = {
                TextButton(onClick = {
                    SettingsManager.resetAll()
                    reload()
                    showResetDialog = false
                }) {
                    Text("Reset", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MonoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}
