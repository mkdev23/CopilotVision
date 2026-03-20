package com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VisionFramePipeline backed by Azure AI Vision (Image Analysis 4.0).
 *
 * Privacy posture:
 *  - Image is sent to your own Azure subscription resource (cvp-demo)
 *  - Azure Computer Vision processes and immediately discards the image
 *  - Only the text caption + tags leave the service — no raw media stored
 *  - gpt-realtime never receives an image, only the text description
 *
 * Endpoint: POST {endpoint}/computervision/imageanalysis:analyze
 *   ?api-version=2023-02-01-preview&features=caption,tags,read
 */
class AzureComputerVisionPipeline : VisionFramePipeline {

    companion object {
        private const val TAG = "AzureComputerVision"
        private const val API_VERSION = "2024-02-01"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun processFrame(jpegBytes: ByteArray, meta: FrameMeta): VisionSignal =
        withContext(Dispatchers.IO) {
            val endpoint = SettingsManager.azureVisionEndpoint.trimEnd('/')
            val key      = SettingsManager.azureVisionKey

            if (endpoint.isBlank() || key.isBlank()) {
                Log.w(TAG, "Azure Computer Vision not configured")
                return@withContext VisionSignal()
            }

            try {
                val features = "caption,tags,read"
                val url = "$endpoint/computervision/imageanalysis:analyze" +
                          "?api-version=$API_VERSION&features=$features&language=en"

                val request = Request.Builder()
                    .url(url)
                    .post(jpegBytes.toRequestBody("image/jpeg".toMediaType()))
                    .addHeader("Ocp-Apim-Subscription-Key", key)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: response.message
                    Log.w(TAG, "Azure Vision returned ${response.code}: $errorBody")
                    return@withContext VisionSignal(error = "HTTP ${response.code}: $errorBody")
                }

                val json = JSONObject(response.body?.string() ?: "{}")

                // Natural language caption e.g. "a person holding a white jar of face cream"
                val caption = json.optJSONObject("captionResult")
                    ?.optString("text", "")
                    ?.takeIf { it.isNotBlank() }

                // Object/scene tags e.g. ["jar", "cream", "hand", "indoor"]
                val tags = json.optJSONObject("tagsResult")
                    ?.optJSONArray("values")
                    ?.let { arr ->
                        (0 until arr.length())
                            .map { arr.getJSONObject(it).optString("name") }
                            .filter { it.isNotBlank() }
                            .take(8) // top 8 tags
                    } ?: emptyList()

                // OCR text from read result
                val ocrText = buildString {
                    val readResult = json.optJSONObject("readResult")
                    val blocks = readResult?.optJSONArray("blocks") ?: return@buildString
                    for (i in 0 until blocks.length()) {
                        val lines = blocks.getJSONObject(i).optJSONArray("lines") ?: continue
                        for (j in 0 until lines.length()) {
                            append(lines.getJSONObject(j).optString("text"))
                            append(" ")
                        }
                    }
                }.trim().takeIf { it.isNotBlank() }

                // Build description: caption is most useful, fall back to tags
                val description = when {
                    caption != null && ocrText != null ->
                        "$caption. Text visible: $ocrText"
                    caption != null -> caption
                    ocrText != null -> "Text visible: $ocrText"
                    tags.isNotEmpty() -> "Scene contains: ${tags.joinToString()}"
                    else -> null
                }

                if (description == null) {
                    Log.d(TAG, "Azure Vision returned no usable content")
                    return@withContext VisionSignal()
                }

                Log.d(TAG, "Vision description: $description")
                VisionSignal(
                    ocrText = description,
                    objects = tags,
                    rawMediaTransmitted = false,
                )

            } catch (e: Exception) {
                Log.e(TAG, "Azure Computer Vision failed: ${e.message}")
                VisionSignal(error = "Exception: ${e.message}")
            }
        }

    override fun release() {
        client.dispatcher.executorService.shutdown()
    }
}
