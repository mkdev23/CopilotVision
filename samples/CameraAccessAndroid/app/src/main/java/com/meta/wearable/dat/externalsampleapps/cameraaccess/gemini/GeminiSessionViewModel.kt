package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.azure.AzureConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.azure.AzureRealtimeService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.azure.FoundryAgentBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp.VisionSignal
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GeminiUiState(
    val isGeminiActive: Boolean = false,
    val connectionState: GeminiConnectionState = GeminiConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val toolCallStatus: ToolCallStatus = ToolCallStatus.Idle,
    val openClawConnectionState: OpenClawConnectionState = OpenClawConnectionState.NotConfigured,
    /** Last description returned by Azure Computer Vision, null if none captured yet. */
    val lastVisionDescription: String? = null,
)

class GeminiSessionViewModel : ViewModel() {
    companion object {
        private const val TAG = "GeminiSessionVM"
    }

    private val _uiState = MutableStateFlow(GeminiUiState())
    val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

    private val azureService = AzureRealtimeService()
    private val foundryBridge = FoundryAgentBridge()
    private var toolCallRouter: ToolCallRouter? = null
    private val audioManager = AudioManager()
    private var stateObservationJob: Job? = null
    private var visionLoopJob: Job? = null

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    /** Set by StreamScreen so VAD speech detection auto-triggers a vision capture. */
    var onSpeechStarted: (() -> Unit)? = null

    /** Set by StreamScreen so the coaching loop can trigger a vision capture. */
    var onProactiveCapture: (() -> Unit)? = null

    /** When true, next injectVisionSignal call is routed as a focus check. */
    private var pendingFocusCheck = false

    fun startSession() {
        if (_uiState.value.isGeminiActive) return

        if (!SettingsManager.isFoundryConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Azure OpenAI not configured. Open Settings → Azure OpenAI and add Endpoint + API Key."
            )
            return
        }

        _uiState.value = _uiState.value.copy(isGeminiActive = true)

        // Wire audio callbacks — AudioManager is unchanged (same PCM16 format)
        audioManager.onAudioCaptured = lambda@{ data ->
            // Phone mode: mute mic while model speaks to prevent echo
            if (streamingMode == StreamingMode.PHONE && azureService.isModelSpeaking.value) return@lambda
            azureService.sendAudio(data)
        }

        azureService.onAudioReceived = { data -> audioManager.playAudio(data) }
        azureService.onInterrupted   = { audioManager.stopPlayback() }
        azureService.onTurnComplete  = { _uiState.value = _uiState.value.copy(userTranscript = "") }

        azureService.onInputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                userTranscript = _uiState.value.userTranscript + text,
                aiTranscript = ""
            )
        }

        azureService.onOutputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                aiTranscript = _uiState.value.aiTranscript + text
            )
        }

        azureService.onSpeechStarted = { onSpeechStarted?.invoke() }

        azureService.onDisconnected = { reason ->
            if (_uiState.value.isGeminiActive) {
                stopSession()
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Connection lost: ${reason ?: "Unknown error"}"
                )
            }
        }

        viewModelScope.launch {
            foundryBridge.checkConnection()
            foundryBridge.resetSession()

            toolCallRouter = ToolCallRouter(foundryBridge, viewModelScope)

            azureService.onToolCall = { toolCall ->
                for (call in toolCall.functionCalls) {
                    toolCallRouter?.handleToolCall(call) { response ->
                        azureService.sendToolResponse(response)
                    }
                }
            }

            azureService.onToolCallCancellation = { cancellation ->
                toolCallRouter?.cancelToolCalls(cancellation.ids)
            }

            // Observe service state — map AzureConnectionState → GeminiConnectionState for UI compat
            stateObservationJob = viewModelScope.launch {
                while (isActive) {
                    delay(100)
                    _uiState.value = _uiState.value.copy(
                        connectionState = azureService.connectionState.value.toGeminiState(),
                        isModelSpeaking = azureService.isModelSpeaking.value,
                        toolCallStatus = foundryBridge.lastToolCallStatus.value,
                        openClawConnectionState = foundryBridge.connectionState.value,
                    )
                }
            }

            azureService.connect { setupOk ->
                if (!setupOk) {
                    val msg = when (val s = azureService.connectionState.value) {
                        is AzureConnectionState.Error -> s.message
                        else -> "Failed to connect to Azure Voice Live"
                    }
                    _uiState.value = _uiState.value.copy(errorMessage = msg)
                    azureService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGeminiActive = false,
                        connectionState = GeminiConnectionState.Disconnected
                    )
                    return@connect
                }

                try {
                    audioManager.startCapture()
                    // Start focus coaching loop if enabled
                    if (SettingsManager.focusCoachingEnabled && SettingsManager.workModeEnabled) {
                        startCoachingLoop()
                    }
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Mic capture failed: ${e.message}"
                    )
                    azureService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGeminiActive = false,
                        connectionState = GeminiConnectionState.Disconnected
                    )
                }
            }
        }
    }

    fun stopSession() {
        toolCallRouter?.cancelAll()
        toolCallRouter = null
        audioManager.stopCapture()
        azureService.disconnect()
        stateObservationJob?.cancel()
        stateObservationJob = null
        visionLoopJob?.cancel()
        visionLoopJob = null
        _uiState.value = GeminiUiState()
    }

    private fun startCoachingLoop() {
        visionLoopJob?.cancel()
        visionLoopJob = viewModelScope.launch {
            val intervalMs = SettingsManager.focusCoachingIntervalSeconds * 1_000L
            delay(intervalMs)  // wait one full interval before first check
            while (isActive && _uiState.value.isGeminiActive) {
                pendingFocusCheck = true
                onProactiveCapture?.invoke()
                delay(intervalMs)
            }
        }
    }

    /**
     * Called by StreamViewModel / PhoneCameraManager after a burst capture completes.
     * Injects the OCR context into the Azure Realtime session so the next voice turn
     * has visual grounding.
     */
    fun injectVisionSignal(signal: VisionSignal) {
        if (!_uiState.value.isGeminiActive) return
        val displayText = when {
            signal.error != null -> "Error: ${signal.error}"
            signal.ocrText != null -> signal.ocrText
            signal.objects.isNotEmpty() -> signal.objects.joinToString()
            else -> "No content returned"
        }
        _uiState.value = _uiState.value.copy(lastVisionDescription = displayText)
        if (pendingFocusCheck) {
            pendingFocusCheck = false
            azureService.sendFocusCheckSignal(signal)
        } else {
            azureService.sendVisionSignal(signal)
        }
    }

    /** No-op — video frames no longer sent directly to the conversation service.
     *  Visual context arrives via [injectVisionSignal] after burst capture. */
    fun sendVideoFrameIfThrottled(bitmap: Bitmap) = Unit

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}

// Map Azure states → GeminiConnectionState so UI composables need no changes
private fun AzureConnectionState.toGeminiState(): GeminiConnectionState = when (this) {
    AzureConnectionState.Disconnected -> GeminiConnectionState.Disconnected
    AzureConnectionState.Connecting   -> GeminiConnectionState.Connecting
    AzureConnectionState.SettingUp    -> GeminiConnectionState.SettingUp
    AzureConnectionState.Ready        -> GeminiConnectionState.Ready
    is AzureConnectionState.Error     -> GeminiConnectionState.Error(message)
}
