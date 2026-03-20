package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus

@Composable
fun GeminiOverlay(
    uiState: GeminiUiState,
    isVisionCapturing: Boolean = false,
    visionNotConfigured: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        // Status bar
        GeminiStatusBar(
            connectionState = uiState.connectionState,
            openClawState = uiState.openClawConnectionState,
            isVisionCapturing = isVisionCapturing,
            visionNotConfigured = visionNotConfigured,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Transcripts
        if (uiState.userTranscript.isNotEmpty() || uiState.aiTranscript.isNotEmpty()) {
            TranscriptView(
                userTranscript = uiState.userTranscript,
                aiTranscript = uiState.aiTranscript,
            )
        }

        // Last vision capture — shows exactly what Azure CV returned
        uiState.lastVisionDescription?.let { desc ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Seen: $desc",
                color = Color(0xFF90CAF9),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // Tool call status
        val toolStatus = uiState.toolCallStatus
        if (toolStatus !is ToolCallStatus.Idle) {
            Spacer(modifier = Modifier.height(4.dp))
            ToolCallStatusView(status = toolStatus)
        }
        // SpeakingIndicator removed — HAL waveform shown centered in StreamScreen
    }
}

@Composable
fun GeminiStatusBar(
    connectionState: GeminiConnectionState,
    openClawState: OpenClawConnectionState,
    isVisionCapturing: Boolean = false,
    visionNotConfigured: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill(
            label = "AI",
            color = when (connectionState) {
                is GeminiConnectionState.Ready -> Color(0xFF4CAF50)
                is GeminiConnectionState.Connecting,
                is GeminiConnectionState.SettingUp -> Color(0xFFFF9800)
                is GeminiConnectionState.Error -> Color(0xFFF44336)
                is GeminiConnectionState.Disconnected -> Color(0xFF9E9E9E)
            },
        )

        if (visionNotConfigured) {
            StatusPill(label = "Vision: not configured", color = Color(0xFFF44336))
        } else if (isVisionCapturing) {
            StatusPill(label = "Vision", color = Color(0xFF2196F3))
        }

        if (openClawState !is OpenClawConnectionState.NotConfigured) {
            StatusPill(
                label = "Foundry",
                color = when (openClawState) {
                    is OpenClawConnectionState.Connected -> Color(0xFF4CAF50)
                    is OpenClawConnectionState.Checking -> Color(0xFFFF9800)
                    is OpenClawConnectionState.Unreachable -> Color(0xFFF44336)
                    is OpenClawConnectionState.NotConfigured -> Color(0xFF9E9E9E)
                },
            )
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun TranscriptView(
    userTranscript: String,
    aiTranscript: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (userTranscript.isNotEmpty()) {
            Text(
                text = userTranscript,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (aiTranscript.isNotEmpty()) {
            Text(
                text = aiTranscript,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ToolCallStatusView(
    status: ToolCallStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (status) {
            is ToolCallStatus.Executing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
            is ToolCallStatus.Completed -> {
                Text(text = "[OK]", color = Color(0xFF4CAF50), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            is ToolCallStatus.Failed -> {
                Text(text = "[X]", color = Color(0xFFF44336), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            is ToolCallStatus.Cancelled -> {
                Text(text = "[--]", color = Color(0xFFFF9800), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            else -> {}
        }
        Text(
            text = status.displayText,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * HAL 9000-inspired centered waveform — displayed full-width in the middle of the screen
 * while the AI is speaking. Pulsing radial glow + symmetric waveform bars.
 * Shown directly in StreamScreen, not inside GeminiOverlay.
 */
@Composable
fun SpeakingWaveform(modifier: Modifier = Modifier) {
    val lightBlue = Color(0xFF4FC3F7)
    val infiniteTransition = rememberInfiniteTransition(label = "hal")
    val barCount = 38

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // Radial glow rings
        Box(
            modifier = Modifier
                .size(320.dp)
                .background(
                    Brush.radialGradient(
                        0.0f to lightBlue.copy(alpha = pulse * 0.6f),
                        0.45f to lightBlue.copy(alpha = pulse * 0.2f),
                        1.0f to Color.Transparent,
                    ),
                    shape = CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    Brush.radialGradient(
                        0.0f to lightBlue.copy(alpha = pulse * 1.2f),
                        0.7f to lightBlue.copy(alpha = pulse * 0.3f),
                        1.0f to Color.Transparent,
                    ),
                    shape = CircleShape,
                ),
        )

        // Waveform bars — symmetric, tallest in the centre
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(barCount) { index ->
                val dist = kotlin.math.abs(index - barCount / 2f) / (barCount / 2f)
                val maxH = (110f * (1f - dist * 0.72f)).coerceAtLeast(8f)
                val dur = 260 + (index % 7) * 65
                val h by infiniteTransition.animateFloat(
                    initialValue = 4f,
                    targetValue = maxH,
                    animationSpec = infiniteRepeatable(
                        animation = tween(dur, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bar$index",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(h.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(lightBlue.copy(alpha = 0.35f + (1f - dist) * 0.65f)),
                )
            }
        }
    }
}
