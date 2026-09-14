package com.aprax.coderm.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.providerDataStore by preferencesDataStore("coder_mobile_providers")

class AiProviderStore(private val context: Context, private val secrets: SecretStore) {
    private val providersKey = stringPreferencesKey("providers")

    val providers: Flow<List<AiProviderConfig>> = context.providerDataStore.data.map { prefs ->
        val raw = prefs[providersKey] ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            buildList {
                repeat(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    add(AiProviderConfig(id, o.optString("name"), o.optString("baseUrl"), o.optString("model"), secrets.getApiKey(id), o.optString("organization"), o.optString("apiVersion"), o.optLong("timeout", 60), o.optInt("maxTokens", 2048), o.optDouble("temperature", 0.2)))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(config: AiProviderConfig) {
        secrets.putApiKey(config.id, config.apiKey)
        context.providerDataStore.edit { prefs ->
            val list = parse(prefs[providersKey] ?: "[]").filterNot { it.id == config.id } + config.copy(apiKey = "")
            prefs[providersKey] = JSONArray(list.map { toJson(it) }).toString()
        }
    }

    suspend fun remove(config: AiProviderConfig) {
        secrets.remove(config.id)
        context.providerDataStore.edit { prefs ->
            prefs[providersKey] = JSONArray(parse(prefs[providersKey] ?: "[]").filterNot { it.id == config.id }.map { toJson(it) }).toString()
        }
    }

    private fun parse(raw: String): List<AiProviderConfig> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            repeat(arr.length()) { i ->
                val o = arr.getJSONObject(i); val id = o.getString("id")
                add(AiProviderConfig(id, o.optString("name"), o.optString("baseUrl"), o.optString("model"), "", o.optString("organization"), o.optString("apiVersion"), o.optLong("timeout", 60), o.optInt("maxTokens", 2048), o.optDouble("temperature", 0.2)))
            }
        }
    }.getOrDefault(emptyList())

    private fun toJson(p: AiProviderConfig) = JSONObject().apply { put("id", p.id); put("name", p.name); put("baseUrl", p.baseUrl); put("model", p.model); put("organization", p.organization); put("apiVersion", p.apiVersion); put("timeout", p.timeoutSeconds); put("maxTokens", p.maxTokens); put("temperature", p.temperature) }
}
