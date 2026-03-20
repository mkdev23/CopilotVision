package com.meta.wearable.dat.externalsampleapps.cameraaccess.graph

import android.util.Log
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
 * Microsoft Graph API client for CVP M365 outputs.
 *
 * Auth: delegated access token (user signs in via Graph Explorer or MSAL).
 * Scopes needed: Tasks.ReadWrite, Chat.ReadWrite, Notes.ReadWrite
 *
 * For hackathon: obtain token at https://developer.microsoft.com/graph/graph-explorer
 * Token lasts ~1 hour. Store in Settings → Microsoft Graph Token.
 */
object GraphApiClient {

    private const val TAG = "GraphApiClient"
    private const val BASE = "https://graph.microsoft.com/v1.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Microsoft To-Do ───────────────────────────────────────────────────────

    /**
     * Creates a task in the user's default Microsoft To-Do list.
     * Returns the created task title on success.
     */
    suspend fun createTodoTask(token: String, title: String, body: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // Use the well-known "Tasks" default list (no list ID lookup needed)
                val listId = getDefaultTodoListId(token)
                    ?: return@withContext Result.failure(Exception("Could not find default To-Do list"))

                val payload = JSONObject().apply {
                    put("title", title)
                    put("importance", "high")
                    if (body.isNotBlank()) {
                        put("body", JSONObject().apply {
                            put("contentType", "text")
                            put("content", body)
                        })
                    }
                }

                val request = Request.Builder()
                    .url("$BASE/me/todo/lists/$listId/tasks")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (response.isSuccessful) {
                    val taskTitle = JSONObject(responseBody).optString("title", title)
                    Log.d(TAG, "To-Do task created: $taskTitle")
                    Result.success(taskTitle)
                } else {
                    Log.w(TAG, "To-Do HTTP ${response.code}: $responseBody")
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "createTodoTask failed: ${e.message}")
                Result.failure(e)
            }
        }

    private fun getDefaultTodoListId(token: String): String? {
        return try {
            val request = Request.Builder()
                .url("$BASE/me/todo/lists")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            if (!response.isSuccessful) return null

            val lists = JSONObject(body).optJSONArray("value") ?: return null
            // Prefer the "Tasks" default list, fall back to first list
            for (i in 0 until lists.length()) {
                val list = lists.getJSONObject(i)
                if (list.optBoolean("isOwner") && list.optString("displayName") == "Tasks") {
                    return list.optString("id")
                }
            }
            if (lists.length() > 0) lists.getJSONObject(0).optString("id") else null
        } catch (e: Exception) {
            Log.e(TAG, "getDefaultTodoListId failed: ${e.message}")
            null
        }
    }

    // ── Microsoft Teams ───────────────────────────────────────────────────────

    /**
     * Sends a message to a Teams chat (1:1 or group).
     * chatId must be pre-configured in Settings.
     */
    suspend fun sendTeamsMessage(token: String, chatId: String, message: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("body", JSONObject().apply {
                        put("content", message)
                    })
                }

                val request = Request.Builder()
                    .url("$BASE/chats/$chatId/messages")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (response.isSuccessful) {
                    Log.d(TAG, "Teams message sent to $chatId")
                    Result.success("Message sent")
                } else {
                    Log.w(TAG, "Teams HTTP ${response.code}: $responseBody")
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendTeamsMessage failed: ${e.message}")
                Result.failure(e)
            }
        }

    // ── Microsoft OneNote ─────────────────────────────────────────────────────

    /**
     * Creates a OneNote page in the user's personal notebook.
     */
    suspend fun createOneNotePage(token: String, title: String, content: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>$title</title></head>
                    <body>
                    <h1>$title</h1>
                    <p>${content.replace("\n", "<br/>")}</p>
                    <p><em>Captured by CVP — Copilot Vision Platform</em></p>
                    </body>
                    </html>
                """.trimIndent()

                val request = Request.Builder()
                    .url("$BASE/me/onenote/pages")
                    .post(html.toRequestBody("text/html".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (response.isSuccessful) {
                    Log.d(TAG, "OneNote page created: $title")
                    Result.success(title)
                } else {
                    Log.w(TAG, "OneNote HTTP ${response.code}: $responseBody")
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "createOneNotePage failed: ${e.message}")
                Result.failure(e)
            }
        }
}
