package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over task-execution backends.
 * [FoundryAgentBridge] (Azure OpenAI) implements this,
 * allowing [ToolCallRouter] to remain backend-agnostic.
 */
interface TaskBridge {
    val lastToolCallStatus: StateFlow<ToolCallStatus>
    val connectionState: StateFlow<OpenClawConnectionState>

    fun setToolCallStatus(status: ToolCallStatus)
    suspend fun checkConnection()
    fun resetSession()
    suspend fun delegateTask(task: String, toolName: String = "execute"): ToolResult
}
