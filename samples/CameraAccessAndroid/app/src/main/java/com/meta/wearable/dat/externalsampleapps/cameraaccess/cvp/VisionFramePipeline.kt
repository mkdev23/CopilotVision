package com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp

/**
 * CVP capture mode — determines which processing pipeline handles frames and audio.
 *
 * PRIVATE  — office/whiteboard use; frames sent to Azure Vision Gateway (OCR/Image Analysis).
 * EXEC     — executive function / work coach; same as PRIVATE plus proactive nudges.
 * CUI      — controlled-unclassified-information stream; all OCR runs on-device via ML Kit,
 *             no media bytes leave the device, text-only upstream.
 * BUILDER  — agent spec generation; observes repeated workflows and drafts agent descriptions.
 */
enum class CvpCaptureMode { PRIVATE, EXEC, CUI, BUILDER }

/**
 * Metadata attached to every captured frame before pipeline dispatch.
 */
data class FrameMeta(
    val timestampMs: Long,
    val mode: CvpCaptureMode,
)

/**
 * Structured output returned by any [VisionFramePipeline] implementation.
 *
 * @param ocrText          Extracted text (whiteboard, terminal, slide, document).
 * @param objects          Detected object/scene tags (e.g. "terminal", "whiteboard").
 * @param uiHint           Active application context hint inferred from OCR content
 *                         (e.g. "Outlook.Email", "Terminal", "Teams").
 * @param rawMediaTransmitted  Always false for CUI mode; may be true only in PRIVATE/EXEC
 *                             when a JPEG was sent to the cloud gateway.
 */
data class VisionSignal(
    val ocrText: String? = null,
    val objects: List<String> = emptyList(),
    val uiHint: String? = null,
    val rawMediaTransmitted: Boolean = false,
    val error: String? = null,
)

/**
 * Contract every frame-processing implementation must satisfy.
 *
 * Callers (StreamViewModel, PhoneCameraManager) dispatch JPEG bytes here;
 * the pipeline decides whether to call the Azure Vision Gateway or run locally.
 */
interface VisionFramePipeline {
    /**
     * Process a single JPEG frame and return structured vision output.
     * Suspend because cloud implementations involve network I/O.
     */
    suspend fun processFrame(jpegBytes: ByteArray, meta: FrameMeta): VisionSignal

    /**
     * Release any resources (HTTP clients, ML Kit detectors, etc.).
     * Called when the capture session ends.
     */
    fun release() {}
}
