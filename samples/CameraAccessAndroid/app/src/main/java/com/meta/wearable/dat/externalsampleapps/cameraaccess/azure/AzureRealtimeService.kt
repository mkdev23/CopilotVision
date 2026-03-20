package com.meta.wearable.dat.externalsampleapps.cameraaccess.azure

import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp.VisionSignal
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiToolCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiToolCallCancellation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ── Connection state (mirrors GeminiConnectionState shape) ───────────────────

sealed class AzureConnectionState {
    data object Disconnected : AzureConnectionState()
    data object Connecting   : AzureConnectionState()
    data object SettingUp    : AzureConnectionState()
    data object Ready        : AzureConnectionState()
    data class  Error(val message: String) : AzureConnectionState()
}

/**
 * Azure Speech Service — Voice Live API (api-version 2025-10-01) WebSocket client.
 *
 * Drop-in replacement for GeminiLiveService: same public callbacks so
 * GeminiSessionViewModel and ToolCallRouter need no changes.
 *
 * Key protocol differences vs Gemini:
 *  - Endpoint: wss://{region}.tts.speech.microsoft.com/cognitiveservices/websocket/v1
 *  - Auth: Ocp-Apim-Subscription-Key header (no query-param key)
 *  - Session setup: "session.update" event (OpenAI Realtime format)
 *  - Audio in:  PCM16 16 kHz — same as Gemini, no AudioManager changes needed
 *  - Audio out: PCM16 24 kHz — same as Gemini
 *  - Tool calls arrive in "response.done" output items (type = "function_call")
 *  - Tool responses sent as "conversation.item.create" (type = "function_call_output")
 *    followed by "response.create" to continue the turn
 */
class AzureRealtimeService {

    companion object {
        private const val TAG = "AzureRealtimeService"
        private const val API_VERSION = "2024-10-01-preview"
    }

    // ── Public state ─────────────────────────────────────────────────────────

    private val _connectionState =
        MutableStateFlow<AzureConnectionState>(AzureConnectionState.Disconnected)
    val connectionState: StateFlow<AzureConnectionState> = _connectionState.asStateFlow()

    private val _isModelSpeaking = MutableStateFlow(false)
    val isModelSpeaking: StateFlow<Boolean> = _isModelSpeaking.asStateFlow()

    // ── Callbacks (same names as GeminiLiveService) ───────────────────────────
    var onAudioReceived:         ((ByteArray) -> Unit)? = null
    var onTurnComplete:          (() -> Unit)?          = null
    var onInterrupted:           (() -> Unit)?          = null
    var onDisconnected:          ((String?) -> Unit)?   = null
    var onInputTranscription:    ((String) -> Unit)?    = null
    var onOutputTranscription:   ((String) -> Unit)?    = null
    var onSpeechStarted:         (() -> Unit)?          = null
    var onToolCall:              ((GeminiToolCall) -> Unit)?             = null
    var onToolCallCancellation:  ((GeminiToolCallCancellation) -> Unit)? = null

    // ── Private ───────────────────────────────────────────────────────────────

    private var webSocket: WebSocket? = null
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private var connectCallback: ((Boolean) -> Unit)? = null
    private var timeoutTimer: Timer? = null

    // Latency tracking
    private var lastUserSpeechEnd: Long = 0
    private var responseLatencyLogged = false

    // Vision timing: track when last model turn completed so we can re-trigger if
    // vision context arrives after the model already responded
    private var lastResponseDoneAt: Long = 0

    // Prevents duplicate response.create calls.
    // Set on speech_stopped (VAD auto-triggers) or when we send response.create manually.
    // Cleared on response.done.
    @Volatile private var responsePending = false

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    // ── Connect / disconnect ──────────────────────────────────────────────────

    fun connect(callback: (Boolean) -> Unit) {
        val endpoint   = SettingsManager.azureOpenAIEndpoint.trimEnd('/')
        val key        = SettingsManager.azureOpenAIKey
        val deployment = SettingsManager.azureOpenAIDeployment.ifBlank { "gpt-realtime" }

        if (endpoint.isBlank() || key.isBlank()) {
            _connectionState.value = AzureConnectionState.Error("Azure OpenAI endpoint/key not configured — open Settings")
            callback(false)
            return
        }

        val wsBase = endpoint.replace(Regex("^https?://"), "wss://")
        val url = "$wsBase/openai/realtime?api-version=$API_VERSION&deployment=$deployment"
        Log.d(TAG, "Connecting to: $url")

        _connectionState.value = AzureConnectionState.Connecting
        connectCallback = callback

        val request = Request.Builder()
            .url(url)
            .addHeader("api-key", key)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                _connectionState.value = AzureConnectionState.SettingUp
                sendSessionUpdate()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = t.message ?: "Unknown error"
                Log.e(TAG, "WebSocket failure: $msg")
                _connectionState.value = AzureConnectionState.Error(msg)
                _isModelSpeaking.value = false
                resolveConnect(false)
                onDisconnected?.invoke(msg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                _connectionState.value = AzureConnectionState.Disconnected
                _isModelSpeaking.value = false
                resolveConnect(false)
                onDisconnected?.invoke("Connection closed ($code: $reason)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = AzureConnectionState.Disconnected
                _isModelSpeaking.value = false
            }
        })

        timeoutTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (_connectionState.value == AzureConnectionState.Connecting
                        || _connectionState.value == AzureConnectionState.SettingUp) {
                        Log.e(TAG, "Connection timed out")
                        _connectionState.value = AzureConnectionState.Error("Connection timed out")
                        resolveConnect(false)
                    }
                }
            }, 15_000)
        }
    }

    fun disconnect() {
        timeoutTimer?.cancel()
        timeoutTimer = null
        webSocket?.close(1000, null)
        webSocket = null
        onToolCall = null
        onToolCallCancellation = null
        _connectionState.value = AzureConnectionState.Disconnected
        _isModelSpeaking.value = false
        resolveConnect(false)
    }

    // ── Send audio ────────────────────────────────────────────────────────────

    fun sendAudio(data: ByteArray) {
        if (_connectionState.value != AzureConnectionState.Ready) return
        sendExecutor.execute {
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", base64)
            }
            webSocket?.send(json.toString())
        }
    }

    // ── Inject a VisionSignal as conversation context ─────────────────────────

    /**
     * Called after a burst capture completes. Injects the OCR result as a
     * conversation item so the model has visual context.
     *
     * If the model has already finished a response turn recently (within 6 s),
     * it means the user asked a question before vision context arrived — we
     * re-trigger response.create so the model answers again with the new context.
     */
    fun sendVisionSignal(signal: VisionSignal) {
        if (_connectionState.value != AzureConnectionState.Ready) return
        if (signal.ocrText == null && signal.objects.isEmpty()) return

        val contextText = buildString {
            append("[CVP Visual Context - CURRENT - ignore all previous CVP Visual Context messages]\n")
            if (signal.ocrText != null) append(signal.ocrText)
            if (signal.objects.isNotEmpty()) append("\nObjects/scene: ${signal.objects.joinToString()}")
            if (signal.uiHint != null) append("\nActive app: ${signal.uiHint}")
        }

        // Re-trigger if model already responded in the last 6 s (timing race: user
        // spoke before vision context arrived, model said "I can't see yet").
        // Don't retrigger if a response is already in-flight (responsePending).
        val shouldRetrigger = !_isModelSpeaking.value &&
            !responsePending &&
            lastResponseDoneAt > 0 &&
            (System.currentTimeMillis() - lastResponseDoneAt) < 6_000L

        sendExecutor.execute {
            val json = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", contextText)
                    }))
                })
            }
            webSocket?.send(json.toString())
            Log.d(TAG, "Injected VisionSignal: retrigger=$shouldRetrigger ocr=${signal.ocrText?.length}chars objects=${signal.objects.size}")

            if (shouldRetrigger) {
                responsePending = true
                webSocket?.send(JSONObject().put("type", "response.create").toString())
                Log.d(TAG, "Re-triggered response after late vision context")
            }
        }
    }

    /**
     * Proactive focus check — injects scene as [FOCUS CHECK] and always triggers a response.
     * The model will either say SKIP (no action needed) or speak a coaching nudge.
     */
    fun sendFocusCheckSignal(signal: VisionSignal) {
        if (_connectionState.value != AzureConnectionState.Ready) return
        if (_isModelSpeaking.value) return  // don't interrupt if model is mid-speech
        if (responsePending) return  // response already queued, skip this check

        val description = signal.ocrText ?: signal.objects.joinToString().takeIf { it.isNotBlank() }
            ?: return  // nothing to analyse

        val contextText = "[FOCUS CHECK]\n$description"

        sendExecutor.execute {
            val json = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", contextText)
                    }))
                })
            }
            webSocket?.send(json.toString())
            responsePending = true
            webSocket?.send(JSONObject().put("type", "response.create").toString())
            Log.d(TAG, "Focus check injected: ${description.take(80)}")
        }
    }

    // ── Tool response (called by ToolCallRouter — accepts Gemini-format JSON) ─

    /**
     * Accepts the Gemini-format toolResponse JSON that ToolCallRouter builds,
     * translates it to Voice Live API format, and sends it.
     * After sending all responses, triggers the next model turn.
     */
    fun sendToolResponse(geminiFormatResponse: JSONObject) {
        sendExecutor.execute {
            try {
                val responses = geminiFormatResponse
                    .optJSONObject("toolResponse")
                    ?.optJSONArray("functionResponses")
                    ?: return@execute

                for (i in 0 until responses.length()) {
                    val item = responses.getJSONObject(i)
                    val callId = item.optString("id")
                    val output = item.optJSONObject("response")?.toString() ?: ""

                    val msg = JSONObject().apply {
                        put("type", "conversation.item.create")
                        put("item", JSONObject().apply {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", output)
                        })
                    }
                    webSocket?.send(msg.toString())
                }

                // Ask model to continue after receiving tool output
                responsePending = true
                webSocket?.send(JSONObject().put("type", "response.create").toString())

            } catch (e: Exception) {
                Log.e(TAG, "sendToolResponse error: ${e.message}")
            }
        }
    }

    fun sendTextMessage(text: String) {
        if (_connectionState.value != AzureConnectionState.Ready) return
        sendExecutor.execute {
            val json = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", text)
                    }))
                })
            }
            webSocket?.send(json.toString())

            // Trigger response immediately for text messages
            responsePending = true
            webSocket?.send(JSONObject().put("type", "response.create").toString())
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun resolveConnect(success: Boolean) {
        val cb = connectCallback
        connectCallback = null
        timeoutTimer?.cancel()
        timeoutTimer = null
        cb?.invoke(success)
    }

    /**
     * session.update — Voice Live API equivalent of Gemini's setup message.
     * Configures: instructions, voice, audio formats, VAD, tools, transcription.
     */
    private fun sendSessionUpdate() {
        val toolsArray = JSONArray().put(JSONObject().apply {
            put("type", "function")
            put("name", "execute")
            put("description", "Your only way to take action. You have no memory, storage, or ability to do anything on your own -- use this tool for everything: sending messages, searching the web, adding to lists, setting reminders, creating notes, research, drafts, scheduling, smart home control, or any request that goes beyond answering a question. When in doubt, use this tool.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("task", JSONObject().apply {
                        put("type", "string")
                        put("description", "Clear, detailed description of what to do. Include all relevant context: names, content, platforms, quantities, etc.")
                    })
                })
                put("required", JSONArray().put("task"))
            })
        })

        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("instructions", SettingsManager.geminiSystemPrompt)
                put("voice", "alloy")
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 300)
                })
                put("max_response_output_tokens", 150)
                put("tools", toolsArray)
                put("tool_choice", "auto")
            })
        }
        webSocket?.send(sessionUpdate.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type")) {

                "session.created", "session.updated" -> {
                    Log.d(TAG, "Session ready ($type)")
                    _connectionState.value = AzureConnectionState.Ready
                    resolveConnect(true)
                }

                "error" -> {
                    val err = json.optJSONObject("error")
                    val msg = err?.optString("message") ?: "Unknown error"
                    Log.e(TAG, "Server error: $msg")
                    _connectionState.value = AzureConnectionState.Error(msg)
                    resolveConnect(false)
                    onDisconnected?.invoke(msg)
                }

                "input_audio_buffer.speech_started" -> {
                    lastUserSpeechEnd = 0
                    responseLatencyLogged = false
                    // Barge-in: cancel the model's audio if it's actively speaking.
                    // Only cancel when audio is playing — not when a tool call is in flight,
                    // since cancelling there would break the requires_action flow.
                    if (_isModelSpeaking.value) {
                        webSocket?.send(JSONObject().put("type", "response.cancel").toString())
                        _isModelSpeaking.value = false
                        // responsePending will be cleared on the "response.cancelled" event
                    }
                    onSpeechStarted?.invoke()
                }

                "input_audio_buffer.speech_stopped" -> {
                    lastUserSpeechEnd = System.currentTimeMillis()
                    responsePending = true  // VAD will auto-trigger a response
                }

                "response.audio.delta" -> {
                    val base64 = json.optString("delta")
                    if (base64.isNotEmpty()) {
                        val audioData = Base64.decode(base64, Base64.DEFAULT)
                        if (!_isModelSpeaking.value) {
                            _isModelSpeaking.value = true
                            if (lastUserSpeechEnd > 0 && !responseLatencyLogged) {
                                val latency = System.currentTimeMillis() - lastUserSpeechEnd
                                Log.d(TAG, "[Latency] ${latency}ms (speech end → first audio)")
                                responseLatencyLogged = true
                            }
                        }
                        onAudioReceived?.invoke(audioData)
                    }
                }

                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta")
                    if (delta.isNotEmpty()) onOutputTranscription?.invoke(delta)
                }

                "response.audio.done" -> {
                    _isModelSpeaking.value = false
                }

                "response.done" -> {
                    _isModelSpeaking.value = false
                    responseLatencyLogged = false
                    responsePending = false
                    lastResponseDoneAt = System.currentTimeMillis()

                    // Parse function calls from response output items
                    val output = json.optJSONObject("response")?.optJSONArray("output")
                    if (output != null) {
                        val calls = mutableListOf<GeminiFunctionCall>()
                        for (i in 0 until output.length()) {
                            val item = output.getJSONObject(i)
                            if (item.optString("type") == "function_call") {
                                val callId = item.optString("call_id")
                                val name   = item.optString("name")
                                val argsStr = item.optString("arguments", "{}")
                                val argsObj = runCatching { JSONObject(argsStr) }.getOrNull()
                                val args = mutableMapOf<String, Any?>()
                                argsObj?.keys()?.forEach { k -> args[k] = argsObj.opt(k) }
                                if (callId.isNotEmpty() && name.isNotEmpty()) {
                                    calls.add(GeminiFunctionCall(callId, name, args))
                                }
                            }
                        }
                        if (calls.isNotEmpty()) {
                            Log.d(TAG, "Tool calls: ${calls.size}")
                            onToolCall?.invoke(GeminiToolCall(calls))
                            return  // don't fire onTurnComplete — wait for tool responses
                        }
                    }
                    onTurnComplete?.invoke()
                }

                "response.cancelled" -> {
                    _isModelSpeaking.value = false
                    responsePending = false
                    onInterrupted?.invoke()
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript")
                    if (transcript.isNotEmpty()) {
                        Log.d(TAG, "You: $transcript")
                        lastUserSpeechEnd = System.currentTimeMillis()
                        responseLatencyLogged = false
                        onInputTranscription?.invoke(transcript)
                    }
                }

                else -> Log.v(TAG, "Unhandled event: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }
}
