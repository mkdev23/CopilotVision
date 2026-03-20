package com.meta.wearable.dat.externalsampleapps.cameraaccess.cvp

import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VisionFramePipeline backed by Azure OpenAI GPT-4o vision (chat completions).
 *
 * Sends a JPEG frame as a base64 image_url to the chat completions endpoint and
 * returns a natural-language description as the VisionSignal. No gateway needed —
 * calls Azure OpenAI directly from the device.
 */
class AzureOpenAIVisionPipeline : VisionFramePipeline {

    companion object {
        private const val TAG = "AzureOpenAIVisionPipeline"
        private const val API_VERSION = "2024-02-15-preview"
        private const val MAX_TOKENS = 200
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun processFrame(jpegBytes: ByteArray, meta: FrameMeta): VisionSignal =
        withContext(Dispatchers.IO) {
            val endpoint = SettingsManager.azureVisionEndpoint.trimEnd('/')
            val key      = SettingsManager.azureVisionKey
            val deploy   = SettingsManager.azureVisionDeployment

            if (endpoint.isBlank() || key.isBlank()) {
                Log.w(TAG, "Azure Vision not configured (endpoint/key blank)")
                return@withContext VisionSignal()
            }

            try {
                val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                val imageUrl    = "data:image/jpeg;base64,$base64Image"

                val prompt = when (meta.mode) {
                    CvpCaptureMode.CUI     -> "List any text visible in this image."
                    CvpCaptureMode.EXEC    -> "Briefly describe what you see in 1-2 sentences. Note any text, documents, screens, or objects relevant to work."
                    CvpCaptureMode.BUILDER -> "Describe this image in detail including all visible text, UI elements, and objects."
                    else                   -> "Briefly describe what you see in 1-2 sentences. Focus on objects, text, and context."
                }

                val messages = JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                                put("detail", "low")  // low detail = faster + cheaper
                            })
                        })
                    })
                })

                val body = JSONObject().apply {
                    put("messages", messages)
                    put("max_tokens", MAX_TOKENS)
                }

                val url = "$endpoint/openai/deployments/$deploy/chat/completions?api-version=$API_VERSION"
                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("api-key", key)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Azure Vision returned ${response.code}: ${response.message}")
                    return@withContext VisionSignal()
                }

                val json        = JSONObject(response.body?.string() ?: "{}")
                val description = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()

                if (description.isNullOrBlank()) {
                    Log.d(TAG, "Empty description from Azure Vision")
                    return@withContext VisionSignal()
                }

                Log.d(TAG, "Vision: $description")
                VisionSignal(ocrText = description, rawMediaTransmitted = false)

            } catch (e: Exception) {
                Log.e(TAG, "Azure Vision call failed: ${e.message}")
                VisionSignal()
            }
        }

    override fun release() {
        client.dispatcher.executorService.shutdown()
    }
}
