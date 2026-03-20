package com.meta.wearable.dat.externalsampleapps.cameraaccess.azure

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.graph.GraphApiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.TaskBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foundry Agent Bridge — routes task execution through Azure AI Foundry agents
 * using the MCP tool server for governed action execution.
 *
 * Primary path (when Foundry project is configured):
 *   POST {foundryEndpoint}/agents/v1.0/threads         → threadId
 *   POST .../threads/{id}/messages                     → inject task + graph token
 *   POST .../threads/{id}/runs                         → run with persistent agent
 *   GET  .../threads/{id}/runs/{runId} (poll)          → wait for completion
 *   GET  .../threads/{id}/messages                     → extract assistant response
 *
 * Foundry agent calls the CVP MCP server (cvp_mcp_server) for tool execution.
 * MCP server routes to Microsoft Graph (OneNote, To-Do, Teams) or SQLite fallback.
 *
 * Fallback path (when Foundry project not configured):
 *   Falls back to Azure OpenAI Chat Completions + direct Graph API calls.
 *   Zero regression — existing behavior preserved.
 */
class FoundryAgentBridge : TaskBridge {

    companion object {
        private const val TAG = "FoundryAgentBridge"
        private const val MAX_HISTORY_TURNS = 10
        private const val CHAT_API_VERSION = "2024-12-01-preview"
        private const val AGENTS_API_VERSION = "2024-05-01-preview"

        // Max polling duration for Foundry runs (ms)
        private const val MAX_POLL_MS = 30_000L
        private const val POLL_INTERVAL_MS = 1_000L

        private val CHAT_COMPLETIONS_SYSTEM_PROMPT = """
            You are CVP — the Copilot Vision Platform action engine for Microsoft 365.
            You are called when the user needs to take action based on what their glasses captured.

            ALWAYS respond with a single JSON object — no markdown, no explanation outside the JSON:
            {
              "action": "create_todo" | "send_teams_message" | "create_onenote" | "none",
              "title": "short title (max 10 words)",
              "content": "full content or message body",
              "response": "brief spoken confirmation for the user (max 20 words, natural, friendly)"
            }

            Action guide:
            - create_todo: tasks, reminders, follow-ups, action items
            - send_teams_message: sharing updates or info with the team
            - create_onenote: capturing notes, whiteboard content, meeting summaries
            - none: questions, analysis, or anything that doesn't need an M365 output
        """.trimIndent()
    }

    private val _lastToolCallStatus =
        MutableStateFlow<ToolCallStatus>(ToolCallStatus.Idle)
    override val lastToolCallStatus: StateFlow<ToolCallStatus> =
        _lastToolCallStatus.asStateFlow()

    private val _connectionState =
        MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.NotConfigured)
    override val connectionState: StateFlow<OpenClawConnectionState> =
        _connectionState.asStateFlow()

    override fun setToolCallStatus(status: ToolCallStatus) {
        _lastToolCallStatus.value = status
    }

    private val conversationHistory = mutableListOf<JSONObject>()

    // Cached MCP tools description, injected into each Foundry thread.
    // Null = not yet fetched this session. Reset by resetSession().
    private var cachedMcpToolsDescription: String? = null

    // Cached learned skills (from get_skills MCP call), injected into each thread.
    // Null = not yet fetched this session. Reset by resetSession().
    private var cachedLearnedSkills: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // ── TaskBridge impl ───────────────────────────────────────────────────────

    override suspend fun checkConnection() = withContext(Dispatchers.IO) {
        if (!SettingsManager.isFoundryConfigured) {
            _connectionState.value = OpenClawConnectionState.NotConfigured
            return@withContext
        }
        _connectionState.value = OpenClawConnectionState.Checking

        // Ping either the Foundry Agents endpoint or Chat Completions fallback
        val url = if (isFoundryAgentMode()) {
            foundryAgentsBaseUrl()
        } else {
            chatUrl()
        }

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("api-key", SettingsManager.azureOpenAIKey)
                .build()
            val response = pingClient.newCall(request).execute()
            val code = response.code
            response.close()
            // Azure returns 405 on GET — means endpoint is reachable
            _connectionState.value = if (code in 200..499) {
                Log.d(TAG, "Foundry reachable (HTTP $code) mode=${if (isFoundryAgentMode()) "agents" else "chat"}")
                OpenClawConnectionState.Connected
            } else {
                OpenClawConnectionState.Unreachable("HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foundry unreachable: ${e.message}")
            _connectionState.value = OpenClawConnectionState.Unreachable(e.message ?: "error")
        }
    }

    override fun resetSession() {
        conversationHistory.clear()
        cachedMcpToolsDescription = null
        cachedLearnedSkills = null
        Log.d(TAG, "Conversation history and skill cache cleared")
    }

    override suspend fun delegateTask(
        task: String,
        toolName: String,
    ): ToolResult = withContext(Dispatchers.IO) {
        _lastToolCallStatus.value = ToolCallStatus.Executing(toolName)

        return@withContext if (isFoundryAgentMode()) {
            delegateViaFoundryAgent(task, toolName)
        } else {
            delegateViaChatCompletions(task, toolName)
        }
    }

    // ── Foundry Agents REST API path (MCP tool routing) ───────────────────────

    /**
     * Routes task through a persistent Foundry agent that calls the CVP MCP server.
     *
     * Graph token is injected into the thread message so the Foundry agent can pass
     * it to MCP tools (create_note, create_task, draft_message) that need M365 access.
     * The token is ephemeral — lives only in the thread context, never stored.
     */
    private suspend fun delegateViaFoundryAgent(task: String, toolName: String): ToolResult {
        val baseUrl = foundryAgentsBaseUrl()
        val agentId = SettingsManager.cvpFoundryAgentId
        val apiKey = SettingsManager.azureOpenAIKey

        return try {
            // Build message content:
            // - inject [GRAPH_TOKEN] so MCP tools can call Microsoft Graph
            // - inject [MCP_TOOLS] so the Foundry agent knows which tools are available
            //   (discovered dynamically from tools/list — cached per session)
            val graphToken = SettingsManager.microsoftGraphToken
            val teamsId = SettingsManager.teamsDefaultChatId
            val mcpTools = discoverMcpTools()
            val learnedSkills = discoverLearnedSkills()
            val messageContent = buildString {
                if (graphToken.isNotBlank()) {
                    appendLine("[GRAPH_TOKEN: $graphToken]")
                    if (teamsId.isNotBlank()) appendLine("[TEAMS_CHAT_ID: $teamsId]")
                    appendLine()
                }
                if (mcpTools.isNotBlank()) {
                    appendLine("[MCP_TOOLS]")
                    appendLine(mcpTools)
                    appendLine("[/MCP_TOOLS]")
                    appendLine()
                }
                if (learnedSkills.isNotBlank()) {
                    appendLine("[LEARNED_SKILLS]")
                    appendLine(learnedSkills)
                    appendLine("[/LEARNED_SKILLS]")
                    appendLine()
                }
                append(task)
            }

            // 1. Create thread
            val threadId = createThread(baseUrl, apiKey) ?: run {
                _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, "Failed to create thread")
                return ToolResult.Failure("Failed to create Foundry thread")
            }

            // 2. Add message to thread
            addMessage(baseUrl, apiKey, threadId, messageContent)

            // 3. Create run with persistent agent
            val runId = createRun(baseUrl, apiKey, threadId, agentId) ?: run {
                _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, "Failed to create run")
                return ToolResult.Failure("Failed to create Foundry run")
            }

            // 4. Poll until run completes
            val runStatus = pollRun(baseUrl, apiKey, threadId, runId)
            if (runStatus != "completed") {
                Log.w(TAG, "Run ended with status: $runStatus")
                _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, "Run status: $runStatus")
                // Clean up thread
                deleteThread(baseUrl, apiKey, threadId)
                return ToolResult.Failure("Foundry run $runStatus")
            }

            // 5. Get last assistant message
            val result = getLastAssistantMessage(baseUrl, apiKey, threadId)
                ?: "Done."

            // 6. Clean up ephemeral thread
            deleteThread(baseUrl, apiKey, threadId)

            Log.d(TAG, "Foundry agent result: ${result.take(200)}")
            _lastToolCallStatus.value = ToolCallStatus.Completed(toolName)
            ToolResult.Success(result)

        } catch (e: Exception) {
            Log.e(TAG, "Foundry agent error: ${e.message}")
            _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, e.message ?: "unknown")
            ToolResult.Failure("Foundry agent error: ${e.message}")
        }
    }

    private fun createThread(baseUrl: String, apiKey: String): String? {
        val request = Request.Builder()
            .url("$baseUrl/threads?api-version=$AGENTS_API_VERSION")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        if (!response.isSuccessful) {
            Log.w(TAG, "createThread HTTP ${response.code}: ${body.take(200)}")
            return null
        }
        return JSONObject(body).optString("id").takeIf { it.isNotBlank() }
    }

    private fun addMessage(baseUrl: String, apiKey: String, threadId: String, content: String) {
        val payload = JSONObject().apply {
            put("role", "user")
            put("content", content)
        }
        val request = Request.Builder()
            .url("$baseUrl/threads/$threadId/messages?api-version=$AGENTS_API_VERSION")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        val response = client.newCall(request).execute()
        response.close()
    }

    private fun createRun(baseUrl: String, apiKey: String, threadId: String, agentId: String): String? {
        val payload = JSONObject().apply {
            put("assistant_id", agentId)
        }
        val request = Request.Builder()
            .url("$baseUrl/threads/$threadId/runs?api-version=$AGENTS_API_VERSION")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        if (!response.isSuccessful) {
            Log.w(TAG, "createRun HTTP ${response.code}: ${body.take(200)}")
            return null
        }
        return JSONObject(body).optString("id").takeIf { it.isNotBlank() }
    }

    private suspend fun pollRun(
        baseUrl: String,
        apiKey: String,
        threadId: String,
        runId: String,
    ): String {
        val deadline = System.currentTimeMillis() + MAX_POLL_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val request = Request.Builder()
                .url("$baseUrl/threads/$threadId/runs/$runId?api-version=$AGENTS_API_VERSION")
                .get()
                .addHeader("api-key", apiKey)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            val runObj = JSONObject(body)
            val status = runObj.optString("status", "unknown")
            Log.v(TAG, "Run $runId status: $status")
            when (status) {
                "completed", "failed", "cancelled", "expired" -> return status
                "requires_action" -> {
                    // Agent wants to call a tool — execute via MCP server, then submit results
                    handleRequiresAction(baseUrl, apiKey, threadId, runId, runObj)
                    // Run transitions back to in_progress after submission; continue polling
                }
            }
        }
        return "timed_out"
    }

    /**
     * Handles a requires_action run state by executing each requested tool call
     * against the CVP MCP server and submitting the outputs back to the Assistants API.
     */
    private fun handleRequiresAction(
        baseUrl: String,
        apiKey: String,
        threadId: String,
        runId: String,
        runObj: JSONObject,
    ) {
        val toolCalls = runObj
            .optJSONObject("required_action")
            ?.optJSONObject("submit_tool_outputs")
            ?.optJSONArray("tool_calls")
            ?: run {
                Log.w(TAG, "requires_action but no tool_calls array found")
                return
            }

        val graphToken = SettingsManager.microsoftGraphToken
        val teamsId = SettingsManager.teamsDefaultChatId
        val outputs = JSONArray()

        for (i in 0 until toolCalls.length()) {
            val call = toolCalls.getJSONObject(i)
            val callId = call.optString("id")
            val funcObj = call.optJSONObject("function") ?: continue
            val funcName = funcObj.optString("name")
            val argsStr = funcObj.optString("arguments", "{}")

            val args = try { JSONObject(argsStr) } catch (e: Exception) { JSONObject() }

            // Unwrap the universal dispatcher: call_mcp_tool(name, arguments)
            // Legacy named tools (pre-dispatcher agent) are also supported.
            val (actualToolName, actualArgs) = if (funcName == "call_mcp_tool") {
                val innerName = args.optString("name")
                val innerArgs = args.optJSONObject("arguments") ?: JSONObject()
                Pair(innerName, innerArgs)
            } else {
                Pair(funcName, args)
            }

            // Inject Graph token + Teams chat ID into the unwrapped args
            if (graphToken.isNotBlank() && !actualArgs.has("graph_token")) {
                actualArgs.put("graph_token", graphToken)
            }
            if (actualToolName == "draft_message" && teamsId.isNotBlank() && !actualArgs.has("teams_chat_id")) {
                actualArgs.put("teams_chat_id", teamsId)
            }

            Log.d(TAG, "Executing MCP tool: $actualToolName (callId=$callId)")
            val result = callMcpTool(actualToolName, actualArgs)
            outputs.put(JSONObject().apply {
                put("tool_call_id", callId)
                put("output", result)
            })
        }

        submitToolOutputs(baseUrl, apiKey, threadId, runId, outputs)
    }

    /**
     * Calls tools/list on the CVP MCP server and returns a compact description of
     * available tools to inject into Foundry threads. Result is cached per session —
     * reset by [resetSession] so new skills deployed to the MCP server are picked up
     * on the next session without requiring an agent update.
     */
    private fun discoverMcpTools(): String {
        cachedMcpToolsDescription?.let { return it }
        val mcpUrl = SettingsManager.cvpMcpServerUrl
        val mcpToken = SettingsManager.cvpMcpBearerToken
        if (mcpUrl.isBlank()) return ""

        val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        val reqBuilder = Request.Builder()
            .url(mcpUrl)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
        if (mcpToken.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $mcpToken")

        val description = try {
            val response = client.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: ""
            response.close()
            val tools = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONArray("tools")
                ?: return ""
            buildString {
                for (i in 0 until tools.length()) {
                    val t = tools.getJSONObject(i)
                    val name = t.optString("name")
                    val desc = t.optString("description").lines().first().take(80)
                    val schema = t.optJSONObject("inputSchema")
                    val required = schema?.optJSONArray("required")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                        ?: emptySet()
                    val props = schema?.optJSONObject("properties")
                    val params = props?.keys()?.asSequence()?.joinToString(", ") { key ->
                        if (key in required) key else "$key?"
                    } ?: ""
                    appendLine("- $name($params) — $desc")
                }
            }.trim()
        } catch (e: Exception) {
            Log.w(TAG, "discoverMcpTools failed: ${e.message}")
            ""
        }
        cachedMcpToolsDescription = description
        Log.d(TAG, "MCP tools discovered: ${description.take(200)}")
        return description
    }

    /**
     * Calls get_skills on the CVP MCP server and returns a formatted string of
     * learned behavioral skills to inject into Foundry threads. Result is cached
     * per session — reset by [resetSession] when the user starts a new conversation.
     */
    private fun discoverLearnedSkills(): String {
        cachedLearnedSkills?.let { return it }
        val mcpUrl = SettingsManager.cvpMcpServerUrl
        val mcpToken = SettingsManager.cvpMcpBearerToken
        if (mcpUrl.isBlank()) return ""

        val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_skills","arguments":{}}}"""
        val reqBuilder = Request.Builder()
            .url(mcpUrl)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
        if (mcpToken.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $mcpToken")

        val description = try {
            val response = client.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: ""
            response.close()
            val skills = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONArray("skills")
                ?: return ""
            if (skills.length() == 0) return ""
            buildString {
                for (i in 0 until skills.length()) {
                    val s = skills.getJSONObject(i)
                    val name = s.optString("name")
                    val instructions = s.optString("instructions")
                    val triggers = s.optString("trigger_phrases")
                    append("[$name]")
                    if (triggers.isNotBlank()) append(" triggers: $triggers")
                    appendLine()
                    appendLine(instructions)
                }
            }.trim()
        } catch (e: Exception) {
            Log.w(TAG, "discoverLearnedSkills failed: ${e.message}")
            ""
        }
        cachedLearnedSkills = description
        if (description.isNotBlank()) Log.d(TAG, "Learned skills: ${description.take(200)}")
        return description
    }

    /**
     * Calls a single tool on the CVP MCP server using JSON-RPC 2.0 over HTTP.
     * Returns the result as a JSON string to submit back to the Assistants API.
     */
    private fun callMcpTool(toolName: String, arguments: JSONObject): String {
        val mcpUrl = SettingsManager.cvpMcpServerUrl
        val mcpToken = SettingsManager.cvpMcpBearerToken

        if (mcpUrl.isBlank()) {
            Log.w(TAG, "MCP server URL not configured — returning stub for $toolName")
            return """{"status":"skipped","reason":"mcp_url_not_configured"}"""
        }

        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            })
        }

        val reqBuilder = Request.Builder()
            .url(mcpUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
        if (mcpToken.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $mcpToken")
        }

        return try {
            val response = client.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: "{}"
            val code = response.code
            response.close()
            Log.d(TAG, "MCP $toolName → HTTP $code: ${body.take(200)}")
            // Unwrap the JSON-RPC result field; fall back to raw body
            val rpc = try { JSONObject(body) } catch (e: Exception) { null }
            rpc?.optJSONObject("result")?.toString()
                ?: rpc?.optString("result")
                ?: body
        } catch (e: Exception) {
            Log.w(TAG, "MCP tool call error: ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    /**
     * Submits tool outputs back to the Assistants API so the run can continue.
     */
    private fun submitToolOutputs(
        baseUrl: String,
        apiKey: String,
        threadId: String,
        runId: String,
        outputs: JSONArray,
    ) {
        val payload = JSONObject().apply { put("tool_outputs", outputs) }
        val request = Request.Builder()
            .url("$baseUrl/threads/$threadId/runs/$runId/submit_tool_outputs?api-version=$AGENTS_API_VERSION")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        Log.d(TAG, "submitToolOutputs HTTP ${response.code}: ${body.take(100)}")
    }

    private fun getLastAssistantMessage(baseUrl: String, apiKey: String, threadId: String): String? {
        val request = Request.Builder()
            .url("$baseUrl/threads/$threadId/messages?api-version=$AGENTS_API_VERSION&order=desc&limit=10")
            .get()
            .addHeader("api-key", apiKey)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()
        val messages = JSONObject(body).optJSONArray("data") ?: return null
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            if (msg.optString("role") == "assistant") {
                val content = msg.optJSONArray("content")
                for (j in 0 until (content?.length() ?: 0)) {
                    val block = content!!.getJSONObject(j)
                    if (block.optString("type") == "text") {
                        return block.optJSONObject("text")?.optString("value")
                    }
                }
            }
        }
        return null
    }

    private fun deleteThread(baseUrl: String, apiKey: String, threadId: String) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/threads/$threadId?api-version=$AGENTS_API_VERSION")
                .delete()
                .addHeader("api-key", apiKey)
                .build()
            client.newCall(request).execute().close()
            Log.d(TAG, "Thread $threadId deleted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete thread: ${e.message}")
        }
    }

    // ── Chat Completions fallback path ────────────────────────────────────────

    private suspend fun delegateViaChatCompletions(task: String, toolName: String): ToolResult {
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", task)
        })
        if (conversationHistory.size > MAX_HISTORY_TURNS * 2) {
            val trimmed = conversationHistory.takeLast(MAX_HISTORY_TURNS * 2)
            conversationHistory.clear()
            conversationHistory.addAll(trimmed)
        }

        return try {
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", CHAT_COMPLETIONS_SYSTEM_PROMPT)
            })
            for (msg in conversationHistory) messages.put(msg)

            val body = JSONObject().apply {
                put("messages", messages)
                put("max_tokens", 1024)
                put("temperature", 0.3)
            }

            val request = Request.Builder()
                .url(chatUrl())
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("api-key", SettingsManager.azureOpenAIKey)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            response.close()

            if (statusCode !in 200..299) {
                Log.w(TAG, "Chat Completions HTTP $statusCode: ${responseBody.take(200)}")
                _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, "HTTP $statusCode")
                return ToolResult.Failure("Foundry returned HTTP $statusCode")
            }

            val content = JSONObject(responseBody)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()

            conversationHistory.add(JSONObject().apply {
                put("role", "assistant")
                put("content", content)
            })

            val userResponse = tryExecuteGraphAction(content, task)
            Log.d(TAG, "Chat Completions result: ${userResponse.take(200)}")
            _lastToolCallStatus.value = ToolCallStatus.Completed(toolName)
            ToolResult.Success(userResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Chat Completions error: ${e.message}")
            _lastToolCallStatus.value = ToolCallStatus.Failed(toolName, e.message ?: "unknown")
            ToolResult.Failure("Foundry error: ${e.message}")
        }
    }

    /**
     * Fallback: parse Chat Completions JSON response and call Graph API directly.
     * Only used when Foundry agent is not configured.
     */
    private suspend fun tryExecuteGraphAction(modelResponse: String, fallbackTask: String): String {
        val cleaned = modelResponse.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val actionJson = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            return modelResponse.ifBlank { "Done." }
        }

        val action   = actionJson.optString("action", "none")
        val title    = actionJson.optString("title", fallbackTask.take(60))
        val content  = actionJson.optString("content", fallbackTask)
        val response = actionJson.optString("response", "Done.")

        val graphToken = SettingsManager.microsoftGraphToken
        if (graphToken.isBlank() || action == "none") return response

        when (action) {
            "create_todo" -> {
                GraphApiClient.createTodoTask(graphToken, title, content)
                    .onSuccess { Log.d(TAG, "To-Do created: $it") }
                    .onFailure { Log.w(TAG, "To-Do failed: ${it.message}") }
            }
            "send_teams_message" -> {
                val chatId = SettingsManager.teamsDefaultChatId
                if (chatId.isNotBlank()) {
                    GraphApiClient.sendTeamsMessage(graphToken, chatId, content)
                        .onSuccess { Log.d(TAG, "Teams message sent") }
                        .onFailure { Log.w(TAG, "Teams failed: ${it.message}") }
                }
            }
            "create_onenote" -> {
                GraphApiClient.createOneNotePage(graphToken, title, content)
                    .onSuccess { Log.d(TAG, "OneNote page created: $it") }
                    .onFailure { Log.w(TAG, "OneNote failed: ${it.message}") }
            }
        }
        return response
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** True when Foundry project endpoint + agent ID are both configured. */
    private fun isFoundryAgentMode(): Boolean =
        SettingsManager.cvpFoundryProjectEndpoint.isNotBlank() &&
        SettingsManager.cvpFoundryAgentId.isNotBlank()

    private fun foundryAgentsBaseUrl(): String =
        SettingsManager.cvpFoundryProjectEndpoint.trimEnd('/') + "/openai"

    private fun chatUrl(): String {
        val endpoint = SettingsManager.azureOpenAIEndpoint.trimEnd('/')
        val deployment = SettingsManager.azureOpenAIDeployment
        return "$endpoint/openai/deployments/$deployment/chat/completions?api-version=$CHAT_API_VERSION"
    }
}
