package com.aprax.coderm.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("coder_mobile")

class ProjectStore(private val context: Context) {
    private val recentKey = stringPreferencesKey("recent_projects")
    private val themeKey = stringPreferencesKey("theme")
    private val autosaveKey = booleanPreferencesKey("autosave")
    private val fontSizeKey = longPreferencesKey("font_size")

    val recentProjects: Flow<List<ProjectRef>> = context.dataStore.data.map { prefs ->
        val raw = prefs[recentKey] ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ProjectRef(
                            uri = Uri.parse(o.getString("uri")),
                            displayName = o.optString("name", "Project"),
                            type = runCatching { ProjectType.valueOf(o.optString("type", "GENERIC")) }.getOrDefault(ProjectType.GENERIC),
                            lastOpenedEpochMs = o.optLong("lastOpened", 0),
                            pinned = o.optBoolean("pinned", false)
                        )
                    )
                }
            }.sortedWith(compareByDescending<ProjectRef> { it.pinned }.thenByDescending { it.lastOpenedEpochMs })
        }.getOrDefault(emptyList())
    }

    val theme: Flow<String> = context.dataStore.data.map { it[themeKey] ?: "system" }
    val autosave: Flow<Boolean> = context.dataStore.data.map { it[autosaveKey] ?: true }
    val fontSize: Flow<Long> = context.dataStore.data.map { it[fontSizeKey] ?: 14L }

    suspend fun rememberProject(project: ProjectRef) {
        context.dataStore.edit { prefs ->
            val existing = parseProjects(prefs[recentKey] ?: "[]").filterNot { it.uri == project.uri }
            val next = (listOf(project.copy(lastOpenedEpochMs = System.currentTimeMillis())) + existing).take(20)
            prefs[recentKey] = JSONArray(next.map { projectToJson(it) }).toString()
        }
    }

    suspend fun removeProject(uri: Uri) {
        context.dataStore.edit { prefs ->
            val next = parseProjects(prefs[recentKey] ?: "[]").filterNot { it.uri == uri }
            prefs[recentKey] = JSONArray(next.map { projectToJson(it) }).toString()
        }
    }

    suspend fun togglePinned(uri: Uri) {
        context.dataStore.edit { prefs ->
            val next = parseProjects(prefs[recentKey] ?: "[]").map { if (it.uri == uri) it.copy(pinned = !it.pinned) else it }
            prefs[recentKey] = JSONArray(next.map { projectToJson(it) }).toString()
        }
    }

    suspend fun saveTheme(value: String) { context.dataStore.edit { it[themeKey] = value } }
    suspend fun saveAutosave(value: Boolean) { context.dataStore.edit { it[autosaveKey] = value } }
    suspend fun saveFontSize(value: Long) { context.dataStore.edit { it[fontSizeKey] = value.coerceIn(10L, 24L) } }

    private fun parseProjects(raw: String): List<ProjectRef> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            repeat(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                add(ProjectRef(Uri.parse(o.getString("uri")), o.optString("name", "Project"), runCatching { ProjectType.valueOf(o.optString("type", "GENERIC")) }.getOrDefault(ProjectType.GENERIC), o.optLong("lastOpened"), o.optBoolean("pinned")))
            }
        }
    }.getOrDefault(emptyList())

    private fun projectToJson(p: ProjectRef) = JSONObject().apply {
        put("uri", p.uri.toString()); put("name", p.displayName); put("type", p.type.name); put("lastOpened", p.lastOpenedEpochMs); put("pinned", p.pinned)
    }
}
