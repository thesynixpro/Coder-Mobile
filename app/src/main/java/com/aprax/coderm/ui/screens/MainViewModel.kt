package com.aprax.coderm.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aprax.coderm.CoderMobileApp
import com.aprax.coderm.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface WorkspaceState {
    data object NoProject : WorkspaceState
    data class Loading(val message: String) : WorkspaceState
    data class Ready(val project: ProjectRef, val files: List<ProjectFile>, val tabs: List<OpenTab>, val activeTab: String?) : WorkspaceState
    data class Error(val message: String) : WorkspaceState
}

enum class Destination { EXPLORER, EDITOR, PREVIEW, AI, TERMINAL, SETTINGS, PROBLEMS, SEARCH }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as CoderMobileApp).container
    val recentProjects = container.projectStore.recentProjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val theme = container.projectStore.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    val autosave = container.projectStore.autosave.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val fontSize = container.projectStore.fontSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 14L)
    val providers = container.providers.providers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _workspace = MutableStateFlow<WorkspaceState>(WorkspaceState.NoProject)
    val workspace: StateFlow<WorkspaceState> = _workspace.asStateFlow()
    private val _destination = MutableStateFlow(Destination.EXPLORER)
    val destination: StateFlow<Destination> = _destination.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _problems = MutableStateFlow<List<Diagnostic>>(emptyList())
    val problems: StateFlow<List<Diagnostic>> = _problems.asStateFlow()

    init {
        viewModelScope.launch {
            recentProjects.firstOrNull()?.firstOrNull()?.let { openProject(it.uri) }
        }
    }

    fun openProject(uri: Uri) {
        viewModelScope.launch {
            _workspace.value = WorkspaceState.Loading("Scanning project…")
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            runCatching {
                val (type, files) = container.projects.scan(uri)
                val root = container.projects.root(uri) ?: error("Project directory is unavailable")
                val project = ProjectRef(uri, root.name ?: "Project", type, System.currentTimeMillis())
                container.projectStore.rememberProject(project)
                _workspace.value = WorkspaceState.Ready(project, files, emptyList(), null)
            }.onFailure { _workspace.value = WorkspaceState.Error(it.message ?: "Unable to open project") }
        }
    }

    fun refresh() {
        val ws = _workspace.value
        if (ws is WorkspaceState.Ready) openProject(ws.project.uri)
    }

    fun setDestination(d: Destination) { _destination.value = d }
    fun setQuery(v: String) { _query.value = v }

    fun openFile(file: ProjectFile) {
        if (file.isDirectory) return
        viewModelScope.launch {
            val ws = _workspace.value as? WorkspaceState.Ready ?: return@launch
            val content = runCatching { container.projects.read(file.uri) }.getOrElse { "// Unable to read file: ${it.message}" }
            val lang = languageFor(file.extension)
            val existing = ws.tabs.firstOrNull { it.path == file.relativePath }
            val tab = existing ?: OpenTab(file.relativePath, file.uri, content, content, lang)
            val tabs = if (existing == null) ws.tabs + tab else ws.tabs
            _workspace.value = ws.copy(tabs = tabs, activeTab = file.relativePath)
            _destination.value = Destination.EDITOR
        }
    }

    fun updateActiveContent(content: String) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        val path = ws.activeTab ?: return
        val tabs = ws.tabs.map { if (it.path == path) it.copy(content = content) else it }
        _workspace.value = ws.copy(tabs = tabs)
        if (autosave.value) saveActive(silent = true)
    }

    fun saveActive(silent: Boolean = false) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        val tab = ws.tabs.firstOrNull { it.path == ws.activeTab } ?: return
        viewModelScope.launch {
            runCatching { container.projects.write(tab.uri, tab.content) }.onSuccess {
                val updated = (_workspace.value as? WorkspaceState.Ready)?.tabs?.map { if (it.path == tab.path) it.copy(savedContent = it.content) else it }
                val current = _workspace.value as? WorkspaceState.Ready
                if (current != null && updated != null) _workspace.value = current.copy(tabs = updated)
            }.onFailure { if (!silent) _workspace.value = WorkspaceState.Error("Unable to save ${tab.path}: ${it.message}") }
        }
    }

    fun createFile(parent: Uri, name: String) {
        viewModelScope.launch { runCatching { container.projects.createFile(parent, name, mimeFor(name)) }.onSuccess { refresh() } }
    }

    fun createFolder(parent: Uri, name: String) { viewModelScope.launch { runCatching { container.projects.createDirectory(parent, name) }.onSuccess { refresh() } } }
    fun delete(uri: Uri) { viewModelScope.launch { runCatching { container.projects.delete(uri) }.onSuccess { refresh() } } }
    fun setTheme(value: String) { viewModelScope.launch { container.projectStore.saveTheme(value) } }
    fun setAutosave(value: Boolean) { viewModelScope.launch { container.projectStore.saveAutosave(value) } }
    fun setFontSize(value: Long) { viewModelScope.launch { container.projectStore.saveFontSize(value) } }
    fun removeRecent(uri: Uri) { viewModelScope.launch { container.projectStore.removeProject(uri) } }
    fun togglePinned(uri: Uri) { viewModelScope.launch { container.projectStore.togglePinned(uri) } }
    fun clearProblems() { _problems.value = emptyList() }

    fun createProject(parentUri: Uri, name: String, template: String) {
        viewModelScope.launch {
            runCatching {
                val dirUri = container.projects.createDirectory(parentUri, name)
                val files = when (template) {
                    "HTML/CSS/JavaScript" -> listOf(Triple("index.html", "text/html", "<!doctype html>\n<html>\n<head><meta charset=\"utf-8\"><title>Coder Mobile</title><link rel=\"stylesheet\" href=\"style.css\"></head>\n<body><main><h1>Hello from Coder Mobile</h1></main><script src=\"app.js\"></script></body>\n</html>\n"), Triple("style.css", "text/css", "body{font-family:system-ui;margin:40px}"), Triple("app.js", "text/javascript", "console.log(\"Hello from Coder Mobile\")"))
                    "Basic Web App" -> listOf(Triple("index.html", "text/html", "<!doctype html>\n<html><body><div id=\"app\"></div><script src=\"app.js\"></script></body></html>\n"), Triple("app.js", "text/javascript", "document.getElementById(\"app\").textContent=\"Hello from Coder Mobile\";"))
                    "TypeScript" -> listOf(Triple("index.ts", "text/plain", "const message: string = \"Hello from Coder Mobile\";\nconsole.log(message);\n"), Triple("tsconfig.json", "application/json", "{\"compilerOptions\":{\"target\":\"ES2022\",\"strict\":true}}\n"))
                    "Node.js" -> listOf(Triple("package.json", "application/json", "{\"name\":\"coder-mobile-project\",\"private\":true,\"scripts\":{\"start\":\"node index.js\"}}\n"), Triple("index.js", "text/javascript", "console.log(\"Hello from Coder Mobile\");\n"))
                    else -> listOf(Triple("README.md", "text/markdown", "# Coder Mobile Project\n\nCreated on Android.\n"))
                }
                for ((filename, mime, content) in files) {
                    val uri = container.projects.createFile(dirUri, filename, mime)
                    container.projects.write(uri, content)
                }
                openProject(dirUri)
            }.onFailure { _workspace.value = WorkspaceState.Error(it.message ?: "Unable to create project") }
        }
    }

    fun saveProvider(config: AiProviderConfig) { viewModelScope.launch { container.providers.save(config) } }
    fun removeProvider(config: AiProviderConfig) { viewModelScope.launch { container.providers.remove(config) } }
    suspend fun testProvider(config: AiProviderConfig): Result<String> = container.ai.test(config)
    suspend fun askAi(config: AiProviderConfig, prompt: String): Result<String> {
        val ws = _workspace.value as? WorkspaceState.Ready
        val active = ws?.tabs?.firstOrNull { it.path == ws.activeTab }
        val context = buildString {
            append("Project: ${ws?.project?.displayName ?: "unknown"}\n")
            append("Project type: ${ws?.project?.type?.label ?: "unknown"}\n")
            append("Active file: ${active?.path ?: "none"}\n")
            if (active != null) append("\n--- file ---\n${active.content.take(12000)}\n--- end file ---\n")
        }
        return container.ai.complete(config, "You are the embedded coding assistant in Coder Mobile. Be precise, project-aware, and propose minimal changes. Never assume unavailable runtimes. Context scope is user-selected.\n\n$context", prompt)
    }

    private fun languageFor(ext: String) = when (ext) {
        "kt", "kts" -> "kotlin"; "ts", "tsx" -> "typescript"; "js", "jsx" -> "javascript"; "html", "css" -> ext; "py" -> "python"; "rs" -> "rust"; "json" -> "json"; else -> "text"
    }
    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java", "txt", "md", "json", "xml", "yaml", "yml" -> "text/plain"
        "html" -> "text/html"; "css" -> "text/css"; "js", "mjs", "cjs" -> "text/javascript"; "ts" -> "text/plain"; else -> "text/plain"
    }
}
