package com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * VisionFramePipeline for PRIVATE and EXEC modes.
 * Encodes the bitmap as JPEG and POSTs it to the CVP Vision Gateway.
 * The gateway calls Azure Vision OCR, discards the frame, returns VisionSignal JSON.
 */
class PrivateModePipeline(
    private val gatewayBaseUrl: String,
    private val bearerToken: String,
) : VisionFramePipeline {

    companion object {
        private const val TAG = "PrivateModePipeline"
        private const val JPEG_QUALITY = 50
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun processFrame(jpegBytes: ByteArray, meta: FrameMeta): VisionSignal =
        withContext(Dispatchers.IO) {
            try {
                val url = "${gatewayBaseUrl.trimEnd('/')}/v1/vision/analyze"
                val body = jpegBytes.toRequestBody("image/jpeg".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("x-cvp-mode", meta.mode.name)
                    .apply {
                        if (bearerToken.isNotBlank()) {
                            addHeader("Authorization", "Bearer $bearerToken")
                        }
                    }
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Gateway returned ${response.code}: ${response.message}")
                    return@withContext VisionSignal()
                }

                val json = JSONObject(response.body?.string() ?: "{}")
                VisionSignal(
                    ocrText = json.optString("ocrText").takeIf { it.isNotBlank() },
                    objects = json.optJSONArray("objects")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    uiHint = json.optString("uiHint").takeIf { it.isNotBlank() },
                    rawMediaTransmitted = json.optBoolean("rawMediaTransmitted", false),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Gateway call failed: ${e.message}")
                VisionSignal()
            }
        }

    override fun release() {
        client.dispatcher.executorService.shutdown()
    }
}

/**
 * Converts a Bitmap to a JPEG ByteArray at the given quality (0–100).
 */
fun Bitmap.toJpegBytes(quality: Int = 50): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}
