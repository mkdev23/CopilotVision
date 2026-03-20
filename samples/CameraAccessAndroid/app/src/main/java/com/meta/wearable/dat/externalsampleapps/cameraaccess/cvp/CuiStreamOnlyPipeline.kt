package com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp

import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * VisionFramePipeline for CUI (Controlled Unclassified Information) mode.
 *
 * ALL processing is on-device via Google ML Kit Text Recognition.
 * No bytes leave the device — [VisionSignal.rawMediaTransmitted] is always false.
 * The JPEG is decoded to a Bitmap, run through the on-device model, then discarded.
 */
class CuiStreamOnlyPipeline : VisionFramePipeline {

    companion object {
        private const val TAG = "CuiStreamOnlyPipeline"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun processFrame(jpegBytes: ByteArray, meta: FrameMeta): VisionSignal {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return VisionSignal()

        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()
                    Log.d(TAG, "On-device OCR: ${text.length} chars")
                    cont.resume(
                        VisionSignal(
                            ocrText = text.ifBlank { null },
                            objects = emptyList(),
                            uiHint = null,          // no cloud call → no hint inference
                            rawMediaTransmitted = false,
                        )
                    )
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit OCR failed: ${e.message}")
                    cont.resume(VisionSignal())
                }
            cont.invokeOnCancellation { /* ML Kit tasks can't be cancelled, just ignore result */ }
        }
    }

    override fun release() {
        recognizer.close()
    }
}
