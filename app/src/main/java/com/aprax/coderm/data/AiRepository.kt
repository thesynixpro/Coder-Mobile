package com.aprax.coderm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

class AiRepository(private val secrets: SecretStore) {
    suspend fun complete(config: AiProviderConfig, system: String, user: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.baseUrl.isNotBlank()) { "Provider endpoint is required" }
            require(URL(config.baseUrl).protocol.equals("https", true)) { "Provider endpoint must use HTTPS" }
            require(config.model.isNotBlank()) { "Model is required" }
            val key = secrets.getApiKey(config.id).ifBlank { config.apiKey }
            require(key.isNotBlank()) { "API key is required" }
            val base = config.baseUrl.trimEnd('/')
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val json = JSONObject().apply {
                put("model", config.model)
                put("temperature", config.temperature.coerceIn(0.0, 2.0))
                put("max_tokens", config.maxTokens.coerceIn(1, 32_000))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(config.timeoutSeconds.coerceIn(5, 180), TimeUnit.SECONDS)
                .readTimeout(config.timeoutSeconds.coerceIn(5, 180), TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(endpoint)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $key")
                .apply { if (config.organization.isNotBlank()) header("OpenAI-Organization", config.organization) }
                .apply { if (config.apiVersion.isNotBlank()) header("api-version", config.apiVersion) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) error(mapProviderError(response.code))
                val root = JSONObject(body)
                root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: error("Provider returned no message content")
            }
        }
    }

    suspend fun test(config: AiProviderConfig): Result<String> =
        complete(config, "You are a connectivity check. Reply only with CONNECTED.", "Reply with CONNECTED.")
            .map { if (it.contains("CONNECTED", true)) "Connected successfully" else "Connected; model returned a response" }

    private fun mapProviderError(code: Int): String = when (code) {
        400 -> "Invalid request"
        401 -> "Invalid credentials"
        403 -> "Unauthorized"
        404 -> "Invalid endpoint or model unavailable"
        408 -> "Request timeout"
        429 -> "Rate limited"
        in 500..599 -> "Provider server error"
        else -> "Provider request failed ($code)"
    }
}
