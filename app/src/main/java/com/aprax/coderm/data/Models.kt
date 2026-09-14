package com.aprax.coderm.data

import android.net.Uri

data class ProjectRef(
    val uri: Uri,
    val displayName: String,
    val type: ProjectType,
    val lastOpenedEpochMs: Long,
    val pinned: Boolean = false
)

enum class ProjectType(val label: String) {
    WEB("Web"), TYPESCRIPT("TypeScript"), NODE("Node.js"), PYTHON("Python"), RUST("Rust"), ANDROID("Android"), GENERIC("Generic")
}

data class ProjectFile(
    val id: String,
    val name: String,
    val relativePath: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L,
    val extension: String = ""
)

data class Diagnostic(
    val severity: Severity,
    val filePath: String,
    val line: Int,
    val column: Int,
    val message: String
)

enum class Severity { ERROR, WARNING, INFO }

data class OpenTab(
    val path: String,
    val uri: Uri,
    val content: String,
    val savedContent: String,
    val language: String,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0
) {
    val isDirty: Boolean get() = content != savedContent
}

data class AiProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val organization: String = "",
    val apiVersion: String = "",
    val timeoutSeconds: Long = 60,
    val maxTokens: Int = 2048,
    val temperature: Double = 0.2
)
