package com.aprax.coderm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiRepository(private val secrets: SecretStore) {
    suspend fun complete(config: AiProviderConfig, system: String, user: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.baseUrl.isNotBlank()) { "Provider endpoint is required" }
            require(config.model.isNotBlank()) { "Model is required" }
            val key = secrets.getApiKey(config.id).ifBlank { config.apiKey }
            require(key.isNotBlank()) { "API key is required" }
            val base = config.baseUrl.trimEnd('/')
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val json = JSONObject().apply {
                put("model", config.model)
                put("temperature", config.temperature)
                put("max_tokens", config.maxTokens)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }
            val client = OkHttpClient.Builder().connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS).readTimeout(config.timeoutSeconds, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(endpoint).post(json.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $key")
                .apply { if (config.organization.isNotBlank()) header("OpenAI-Organization", config.organization) }
                .apply { if (config.apiVersion.isNotBlank()) header("api-version", config.apiVersion) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) error(mapProviderError(response.code))
                val root = JSONObject(body)
                root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }
    }

    suspend fun test(config: AiProviderConfig): Result<String> = withContext(Dispatchers.IO) {
        complete(config, "You are a connectivity check. Reply only with CONNECTED.", "Reply with CONNECTED.")
            .map { if (it.contains("CONNECTED", ignoreCase = true)) "Connected successfully" else "Connected; model returned a response" }
    }

    private fun mapProviderError(code: Int): String = when (code) {
        401 -> "Invalid credentials"
        403 -> "Unauthorized"
        404 -> "Invalid endpoint or model unavailable"
        408 -> "Request timeout"
        429 -> "Rate limited"
        in 500..599 -> "Provider server error"
        else -> "Provider request failed ($code)"
    }
}
