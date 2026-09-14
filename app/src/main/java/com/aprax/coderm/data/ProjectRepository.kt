package com.aprax.coderm.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProjectRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun root(uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(context, uri)

    suspend fun scan(uri: Uri): Pair<ProjectType, List<ProjectFile>> = withContext(Dispatchers.IO) {
        val root = root(uri) ?: error("Project directory is no longer accessible")
        val files = ArrayList<ProjectFile>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to "")
        val maxFiles = 10000
        while (stack.isNotEmpty() && files.size < maxFiles) {
            val (dir, prefix) = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
                .sortedBy { it.name?.lowercase() ?: "" }
            for (child in children) {
                val name = child.name ?: continue
                val rel = if (prefix.isBlank()) name else "$prefix/$name"
                val ext = name.substringAfterLast('.', "").lowercase()
                files += ProjectFile(child.uri.toString(), name, rel, child.uri, child.isDirectory, child.length(), child.lastModified(), ext)
                if (child.isDirectory) stack.add(child to rel)
            }
        }
        detectType(files) to files
    }

    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.bufferedReader().use { it?.readText() ?: "" }
    }

    suspend fun write(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter().use { writer ->
            checkNotNull(writer) { "Unable to open file for writing" }
            writer.write(content)
            writer.flush()
        }
    }

    suspend fun createFile(parent: Uri, name: String, mime: String): Uri = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, parent) ?: error("Directory is unavailable")
        val safeName = name.trim().ifBlank { error("File name is required") }
        dir.findFile(safeName)?.let { error("A file or folder named '$safeName' already exists") }
        dir.createFile(mime, safeName)?.uri ?: error("Could not create file")
    }

    suspend fun createDirectory(parent: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, parent) ?: error("Directory is unavailable")
        dir.createDirectory(name.trim())?.uri ?: error("Could not create folder")
    }

    suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
        DocumentFile.fromSingleUri(context, uri)?.let { check(it.delete()) { "Delete failed" } }
    }

    private fun detectType(files: List<ProjectFile>): ProjectType {
        val paths = files.map { it.relativePath.lowercase() }.toSet()
        return when {
            "build.gradle" in paths || paths.any { it.endsWith("/settings.gradle") || it == "settings.gradle" } -> ProjectType.ANDROID
            "package.json" in paths && paths.any { it.endsWith("tsconfig.json") } -> ProjectType.TYPESCRIPT
            "package.json" in paths -> ProjectType.NODE
            paths.any { it.endsWith("requirements.txt") || it.endsWith("pyproject.toml") } -> ProjectType.PYTHON
            paths.any { it.endsWith("cargo.toml") } -> ProjectType.RUST
            paths.any { it.endsWith("index.html") || it.endsWith("vite.config.js") || it.endsWith("vite.config.ts") } -> ProjectType.WEB
            else -> ProjectType.GENERIC
        }
    }
}
