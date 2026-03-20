package com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Controls burst-capture windows for CVP.
 *
 * Burst capture is the privacy gate: frames are only dispatched to the
 * VisionFramePipeline while a burst is active. A burst:
 *   - starts on explicit user action (tap + optional voice confirm)
 *   - auto-stops after [durationMs] unless the user re-authorises
 *   - can be killed immediately via [stopImmediate]
 *
 * Work Mode must be ON before [startBurst] has any effect.
 */
class BurstCaptureController(
    private val scope: CoroutineScope,
    val durationMs: Long = 3_000L,
) {
    companion object {
        private const val TAG = "BurstCaptureController"
    }

    private val _state = MutableStateFlow(BurstState.IDLE)
    val state: StateFlow<BurstState> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value == BurstState.ACTIVE

    private var autoStopJob: Job? = null

    /**
     * Start a burst window. No-op if already active.
     * [onComplete] is invoked when the burst ends (timeout or kill switch).
     */
    fun startBurst(onComplete: (() -> Unit)? = null) {
        if (_state.value == BurstState.ACTIVE) {
            Log.d(TAG, "startBurst: already active, ignoring")
            return
        }
        _state.value = BurstState.ACTIVE
        Log.d(TAG, "Burst started (${durationMs}ms window)")

        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(durationMs)
            if (_state.value == BurstState.ACTIVE) {
                _state.value = BurstState.IDLE
                Log.d(TAG, "Burst auto-stopped after ${durationMs}ms")
                onComplete?.invoke()
            }
        }
    }

    /**
     * Immediately halt all capture — kill switch gesture.
     */
    fun stopImmediate() {
        autoStopJob?.cancel()
        autoStopJob = null
        if (_state.value != BurstState.IDLE) {
            _state.value = BurstState.IDLE
            Log.d(TAG, "Burst killed immediately")
        }
    }

    /**
     * Returns true if the controller should allow a frame through.
     * Callers wrap their frame-dispatch logic with this check.
     */
    fun shouldPassFrame(): Boolean = _state.value == BurstState.ACTIVE
}

enum class BurstState { IDLE, ACTIVE }
