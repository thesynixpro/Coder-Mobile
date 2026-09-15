package com.aprax.coderm.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class ProjectRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun root(uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)

    suspend fun scan(uri: Uri): Pair<ProjectType, List<ProjectFile>> = withContext(Dispatchers.IO) {
        val root = root(uri) ?: error("Project directory is no longer accessible")
        val files = ArrayList<ProjectFile>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to "")
        val maxEntries = 20_000
        while (stack.isNotEmpty() && files.size < maxEntries) {
            val (dir, prefix) = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
                .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" })
            for (child in children) {
                val name = child.name ?: continue
                val rel = if (prefix.isBlank()) name else "$prefix/$name"
                val ext = if (child.isDirectory) "" else name.substringAfterLast('.', "").lowercase()
                files += ProjectFile(
                    id = child.uri.toString(),
                    name = name,
                    relativePath = rel,
                    uri = child.uri,
                    isDirectory = child.isDirectory,
                    size = child.length(),
                    modified = child.lastModified(),
                    extension = ext
                )
                if (child.isDirectory && name !in IGNORED_DIRECTORY_NAMES) stack.add(child to rel)
            }
        }
        detectType(files) to files.sortedWith(compareBy<ProjectFile> { it.relativePath.count { c -> c == '/' } }.thenBy { !it.isDirectory }.thenBy { it.relativePath.lowercase() })
    }

    suspend fun read(uri: Uri, maxBytes: Long = 2_000_000): String = withContext(Dispatchers.IO) {
        val size = runCatching { DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L }.getOrDefault(0L)
        require(size <= maxBytes) { "File is too large to edit in the mobile editor (${size / 1024} KB)" }
        resolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
        } ?: error("Unable to read file")
    }

    suspend fun write(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            checkNotNull(writer) { "Unable to open file for writing" }
            writer.write(content)
            writer.flush()
        }
    }

    suspend fun createFile(parent: Uri, name: String, mime: String): Uri = withContext(Dispatchers.IO) {
        val dir = directory(parent) ?: error("Directory is unavailable")
        val safeName = sanitizeName(name)
        dir.findFile(safeName)?.let { error("A file or folder named '$safeName' already exists") }
        dir.createFile(mime, safeName)?.uri ?: error("Could not create file")
    }

    suspend fun createDirectory(parent: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        val dir = directory(parent) ?: error("Directory is unavailable")
        val safeName = sanitizeName(name)
        dir.findFile(safeName)?.let { error("A file or folder named '$safeName' already exists") }
        dir.createDirectory(safeName)?.uri ?: error("Could not create folder")
    }

    suspend fun rename(uri: Uri, newName: String) = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromSingleUri(context, uri) ?: error("Item is unavailable")
        check(file.renameTo(sanitizeName(newName))) { "Rename failed" }
    }

    suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromSingleUri(context, uri) ?: error("Item is unavailable")
        check(file.delete()) { "Delete failed" }
    }

    suspend fun duplicate(uri: Uri, parentUri: Uri): Uri = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromSingleUri(context, uri) ?: error("Source is unavailable")
        val targetDir = directory(parentUri) ?: error("Target directory is unavailable")
        copyRecursively(source, targetDir, source.name ?: "Copy")
    }

    suspend fun move(uri: Uri, parentUri: Uri): Uri = withContext(Dispatchers.IO) {
        val copied = duplicate(uri, parentUri)
        val source = DocumentFile.fromSingleUri(context, uri) ?: error("Source is unavailable")
        check(source.delete()) { "Move copied the item but could not remove the original" }
        copied
    }

    suspend fun importFile(sourceUri: Uri, parentUri: Uri, suggestedName: String? = null): Uri = withContext(Dispatchers.IO) {
        val targetDir = directory(parentUri) ?: error("Target directory is unavailable")
        val name = sanitizeName(suggestedName ?: DocumentFile.fromSingleUri(context, sourceUri)?.name ?: "imported-file")
        val mime = resolver.getType(sourceUri) ?: "application/octet-stream"
        targetDir.findFile(name)?.let { error("A file named '$name' already exists") }
        val target = targetDir.createFile(mime, name) ?: error("Could not create imported file")
        resolver.openInputStream(sourceUri)?.use { input ->
            resolver.openOutputStream(target.uri, "wt")?.use { output -> input.copyTo(output) }
                ?: error("Could not write imported file")
        } ?: error("Could not read imported file")
        target.uri
    }

    private fun copyRecursively(source: DocumentFile, targetDir: DocumentFile, requestedName: String): Uri {
        val safeName = uniqueName(targetDir, requestedName, source.isDirectory)
        if (source.isDirectory) {
            val newDir = targetDir.createDirectory(safeName) ?: error("Could not create destination folder")
            source.listFiles().forEach { child -> copyRecursively(child, newDir, child.name ?: "item") }
            return newDir.uri
        }
        val mime = resolver.getType(source.uri) ?: mimeFor(safeName)
        val target = targetDir.createFile(mime, safeName) ?: error("Could not create destination file")
        resolver.openInputStream(source.uri)?.use { input ->
            resolver.openOutputStream(target.uri, "wt")?.use { output -> input.copyTo(output) }
                ?: error("Could not write destination file")
        } ?: error("Could not read source file")
        return target.uri
    }

    private fun directory(uri: Uri): DocumentFile? = listOf(
        DocumentFile.fromTreeUri(context, uri),
        DocumentFile.fromSingleUri(context, uri)
    ).firstOrNull { it?.isDirectory == true }

    private fun uniqueName(dir: DocumentFile, original: String, directory: Boolean): String {
        if (dir.findFile(original) == null) return original
        val dot = if (!directory) original.lastIndexOf('.') else -1
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var i = 2
        while (dir.findFile("$base ($i)$ext") != null) i++
        return "$base ($i)$ext"
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.trim().replace('/', '_').replace('\\', '_')
        require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") { "A valid name is required" }
        return cleaned
    }

    private val IGNORED_DIRECTORY_NAMES = setOf(".git", ".gradle", "node_modules", "build", ".idea")

    private fun detectType(files: List<ProjectFile>): ProjectType {
        val paths = files.map { it.relativePath.lowercase() }.toSet()
        return when {
            paths.any { it == "build.gradle" || it == "build.gradle.kts" || it.endsWith("/build.gradle") || it.endsWith("/build.gradle.kts") } &&
                paths.any { it == "settings.gradle" || it == "settings.gradle.kts" } -> ProjectType.ANDROID
            "package.json" in paths && paths.any { it.endsWith("tsconfig.json") } -> ProjectType.TYPESCRIPT
            "package.json" in paths -> ProjectType.NODE
            paths.any { it.endsWith("requirements.txt") || it.endsWith("pyproject.toml") } -> ProjectType.PYTHON
            paths.any { it.endsWith("cargo.toml") } -> ProjectType.RUST
            paths.any { it.endsWith("index.html") || it.endsWith("vite.config.js") || it.endsWith("vite.config.ts") || it.endsWith("vite.config.mjs") } -> ProjectType.WEB
            else -> ProjectType.GENERIC
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js", "mjs", "cjs" -> "text/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> "text/plain"
    }
}
