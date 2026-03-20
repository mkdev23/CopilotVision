package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp.CvpCaptureMode

object SettingsManager {
    private const val PREFS_NAME = "visionclaw_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var geminiAPIKey: String
        get() = prefs.getString("geminiAPIKey", "") ?: ""
        set(value) = prefs.edit().putString("geminiAPIKey", value).apply()

    var geminiSystemPrompt: String
        get() = prefs.getString("geminiSystemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString("geminiSystemPrompt", value).apply()

    // ── CVP Foundry Agent (MCP routing) ─────────────────────────────────────

    /**
     * Azure AI Foundry project endpoint for the Foundry Agents REST API.
     * Found in: Foundry portal → your project → Overview → Endpoint.
     * e.g. https://cvp.eastus2.inference.ml.azure.com
     * Leave blank to fall back to Chat Completions mode.
     */
    var cvpFoundryProjectEndpoint: String
        get() = prefs.getString("cvpFoundryProjectEndpoint", "") ?: ""
        set(value) = prefs.edit().putString("cvpFoundryProjectEndpoint", value).apply()

    /**
     * Foundry persistent agent ID — created by agent_service/agent.py.
     * e.g. asst_xxxxxxxxxxxx
     * Leave blank to fall back to Chat Completions mode.
     */
    var cvpFoundryAgentId: String
        get() = prefs.getString("cvpFoundryAgentId", "") ?: ""
        set(value) = prefs.edit().putString("cvpFoundryAgentId", value).apply()

    /**
     * CVP MCP Server URL — used by FoundryAgentBridge to execute tool calls
     * when the Foundry agent returns requires_action.
     * e.g. https://cvp-mcp-server.orangeflower-68897f93.eastus2.azurecontainerapps.io/mcp
     */
    var cvpMcpServerUrl: String
        get() = prefs.getString("cvpMcpServerUrl", "") ?: ""
        set(value) = prefs.edit().putString("cvpMcpServerUrl", value).apply()

    /**
     * Bearer token for the CVP MCP Server (must match MCP_BEARER_TOKEN on the server).
     */
    var cvpMcpBearerToken: String
        get() = prefs.getString("cvpMcpBearerToken", "") ?: ""
        set(value) = prefs.edit().putString("cvpMcpBearerToken", value).apply()

    var webrtcSignalingURL: String
        get() = prefs.getString("webrtcSignalingURL", "") ?: ""
        set(value) = prefs.edit().putString("webrtcSignalingURL", value).apply()

    var videoStreamingEnabled: Boolean
        get() = prefs.getBoolean("videoStreamingEnabled", true)
        set(value) = prefs.edit().putBoolean("videoStreamingEnabled", value).apply()

    var proactiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean("proactiveNotificationsEnabled", true)
        set(value) = prefs.edit().putBoolean("proactiveNotificationsEnabled", value).apply()

    /** ADHD focus coaching — proactive check-ins when the scene suggests the user drifted. */
    var focusCoachingEnabled: Boolean
        get() = prefs.getBoolean("focusCoachingEnabled", false)
        set(value) = prefs.edit().putBoolean("focusCoachingEnabled", value).apply()

    /** How often (seconds) the coaching loop captures and checks focus. Default 60 s. */
    var focusCoachingIntervalSeconds: Int
        get() = prefs.getInt("focusCoachingIntervalSeconds", 60)
        set(value) = prefs.edit().putInt("focusCoachingIntervalSeconds", value).apply()

    // ── CVP settings ────────────────────────────────────────────────────────

    /** Work Mode gate — OFF by default; no capture happens unless this is true. */
    var workModeEnabled: Boolean
        get() = prefs.getBoolean("cvpWorkModeEnabled", false)
        set(value) = prefs.edit().putBoolean("cvpWorkModeEnabled", value).apply()

    /** Active CVP policy mode. */
    var cvpCaptureMode: CvpCaptureMode
        get() = CvpCaptureMode.valueOf(
            prefs.getString("cvpCaptureMode", CvpCaptureMode.PRIVATE.name)
                ?: CvpCaptureMode.PRIVATE.name
        )
        set(value) = prefs.edit().putString("cvpCaptureMode", value.name).apply()

    /** How long a burst window stays open before auto-stopping (milliseconds). */
    var burstCaptureDurationMs: Long
        get() = prefs.getLong("cvpBurstDurationMs", 3_000L)
        set(value) = prefs.edit().putLong("cvpBurstDurationMs", value).apply()

    /** Azure Vision Gateway base URL (e.g. https://cvpac.{region}.azurecontainerapps.io). */
    var cvpGatewayUrl: String
        get() = prefs.getString("cvpGatewayUrl", "") ?: ""
        set(value) = prefs.edit().putString("cvpGatewayUrl", value).apply()

    /** Bearer token for CVP Vision Gateway (issued by Azure AD app registration). */
    var cvpGatewayToken: String
        get() = prefs.getString("cvpGatewayToken", "") ?: ""
        set(value) = prefs.edit().putString("cvpGatewayToken", value).apply()

    /** Azure Speech Service region (e.g. "eastus") — used by Voice Live API. */
    var azureSpeechRegion: String
        get() = prefs.getString("azureSpeechRegion", "eastus") ?: "eastus"
        set(value) = prefs.edit().putString("azureSpeechRegion", value).apply()

    /** Azure Speech Service key — used by Voice Live API. */
    var azureSpeechKey: String
        get() = prefs.getString("azureSpeechKey", "") ?: ""
        set(value) = prefs.edit().putString("azureSpeechKey", value).apply()

    /** Azure OpenAI endpoint for Foundry agent task execution (e.g. https://Cvp-Websocket.openai.azure.com/). */
    var azureOpenAIEndpoint: String
        get() = prefs.getString("azureOpenAIEndpoint", "") ?: ""
        set(value) = prefs.edit().putString("azureOpenAIEndpoint", value).apply()

    /** Azure OpenAI API key for Foundry agent. */
    var azureOpenAIKey: String
        get() = prefs.getString("azureOpenAIKey", "") ?: ""
        set(value) = prefs.edit().putString("azureOpenAIKey", value).apply()

    /** Azure OpenAI deployment name (e.g. "gpt-4o"). */
    var azureOpenAIDeployment: String
        get() = prefs.getString("azureOpenAIDeployment", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("azureOpenAIDeployment", value).apply()

    /** Microsoft Graph delegated access token — get from graph.microsoft.com/graph-explorer */
    var microsoftGraphToken: String
        get() = prefs.getString("microsoftGraphToken", "") ?: ""
        set(value) = prefs.edit().putString("microsoftGraphToken", value).apply()

    /** Teams chat ID for outbound messages (1:1 or group chat) */
    var teamsDefaultChatId: String
        get() = prefs.getString("teamsDefaultChatId", "") ?: ""
        set(value) = prefs.edit().putString("teamsDefaultChatId", value).apply()

    val isSpeechConfigured: Boolean
        get() = azureSpeechRegion.isNotBlank() && azureSpeechKey.isNotBlank()

    val isFoundryConfigured: Boolean
        get() = azureOpenAIEndpoint.isNotBlank() && azureOpenAIKey.isNotBlank()

    /** Azure OpenAI endpoint for vision analysis (can be the same resource as the realtime model). */
    var azureVisionEndpoint: String
        get() = prefs.getString("azureVisionEndpoint", "") ?: ""
        set(value) = prefs.edit().putString("azureVisionEndpoint", value).apply()

    /** Azure OpenAI key for vision analysis. */
    var azureVisionKey: String
        get() = prefs.getString("azureVisionKey", "") ?: ""
        set(value) = prefs.edit().putString("azureVisionKey", value).apply()

    /** Deployment name for vision analysis (must support vision, e.g. gpt-4o). */
    var azureVisionDeployment: String
        get() = prefs.getString("azureVisionDeployment", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("azureVisionDeployment", value).apply()

    val isVisionConfigured: Boolean
        get() = azureVisionEndpoint.isNotBlank() && azureVisionKey.isNotBlank()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    const val DEFAULT_SYSTEM_PROMPT = """You are a voice-first AI assistant. ALWAYS respond in 1-2 short spoken sentences. Never explain your reasoning. Never list steps. Just answer and stop.

You are a focus coach and AI assistant for someone with ADHD wearing Meta Ray-Ban smart glasses at work. You are voice-first — keep all responses short and natural for spoken conversation.

VISION: The glasses camera captures a frame when the user speaks. Scene description is injected as [CVP Visual Context - CURRENT]. Always use the most recent one.
When you have visual context: use it confidently — describe what you see, read visible text, name objects.
When asked about vision and no context arrived yet: say "I'm looking now, give me a moment." Never say you cannot see.

FOCUS COACHING: When you receive a [FOCUS CHECK] message, you are doing a proactive well-being check. Analyse whether the user appears to be at their workstation and engaged with work.
- If they appear focused at their desk: respond with only the word SKIP and nothing else.
- If the scene suggests they stepped away, are looking away from their screen, or appear distracted: give one short warm nudge. Examples: "Hey, looks like you drifted a bit — ready to jump back in?" or "Noticed you stepped away — planned break or keep going?" or "Your focus shifted — want a quick reset?"
- Never be harsh. Keep nudges under 15 words. Don't repeat if they just acknowledged one.

CAPABILITIES: You have one tool: execute — connects to a personal assistant that can send messages, search the web, manage lists, set reminders, create notes, control apps, and more.
ALWAYS use execute for: sending messages, searching, adding/creating anything, researching, controlling apps.
Before calling execute, speak a brief acknowledgment first. Never call execute silently."""
}
