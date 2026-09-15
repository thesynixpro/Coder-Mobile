package com.aprax.coderm.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aprax.coderm.CoderMobileApp
import com.aprax.coderm.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed interface WorkspaceState {
    data object NoProject : WorkspaceState
    data class Loading(val message: String) : WorkspaceState
    data class Ready(
        val project: ProjectRef,
        val files: List<ProjectFile>,
        val tabs: List<OpenTab>,
        val activeTab: String?
    ) : WorkspaceState
    data class Error(val message: String) : WorkspaceState
}

enum class Destination { EXPLORER, EDITOR, PREVIEW, AI, TERMINAL, SETTINGS, PROBLEMS, SEARCH, BUILD, GIT }

enum class AiContextScope { CURRENT_FILE, OPEN_FILES, PROJECT }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as CoderMobileApp).container
    val recentProjects = container.projectStore.recentProjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val theme = container.projectStore.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    val autosave = container.projectStore.autosave.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val fontSize = container.projectStore.fontSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 14L)
    val tabSize = container.projectStore.tabSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)
    val wordWrap = container.projectStore.wordWrap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val providers = container.providers.providers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val defaultProviderId = container.projectStore.defaultProviderId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _workspace = MutableStateFlow<WorkspaceState>(WorkspaceState.NoProject)
    val workspace: StateFlow<WorkspaceState> = _workspace.asStateFlow()
    private val _destination = MutableStateFlow(Destination.EXPLORER)
    val destination: StateFlow<Destination> = _destination.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _problems = MutableStateFlow<List<Diagnostic>>(emptyList())
    val problems: StateFlow<List<Diagnostic>> = _problems.asStateFlow()
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _proposal = MutableStateFlow<AiChangeProposal?>(null)
    val proposal: StateFlow<AiChangeProposal?> = _proposal.asStateFlow()

    private var autosaveJob: Job? = null
    private var copiedUri: Uri? = null
    private val closedTabs = ArrayDeque<OpenTab>()

    init {
        viewModelScope.launch {
            recentProjects.firstOrNull()?.firstOrNull()?.let { project -> openProject(project.uri, automatic = true) }
        }
    }

    fun clearStatus() { _status.value = null }
    fun setDestination(destination: Destination) { _destination.value = destination }
    fun setQuery(value: String) { _query.value = value }

    fun openProject(uri: Uri, automatic: Boolean = false) {
        viewModelScope.launch {
            _workspace.value = WorkspaceState.Loading(if (automatic) "Restoring last workspace…" else "Scanning project…")
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            loadProject(uri, preserveTabs = false)
        }
    }

    private suspend fun loadProject(uri: Uri, preserveTabs: Boolean) {
        runCatching {
            val current = _workspace.value as? WorkspaceState.Ready
            val (type, files) = container.projects.scan(uri)
            val root = container.projects.root(uri) ?: error("Project directory is unavailable")
            val project = ProjectRef(uri, root.name ?: "Project", type, System.currentTimeMillis(), current?.project?.pinned ?: false)
            container.projectStore.rememberProject(project)
            val keptTabs = if (preserveTabs) current?.tabs?.filter { tab -> files.any { it.relativePath == tab.path && !it.isDirectory } }.orEmpty() else emptyList()
            val active = if (preserveTabs && current?.activeTab != null && keptTabs.any { it.path == current.activeTab }) current.activeTab else keptTabs.firstOrNull()?.path
            _workspace.value = WorkspaceState.Ready(project, files, keptTabs, active)
            if (preserveTabs) runDiagnostics()
        }.onFailure {
            _workspace.value = WorkspaceState.Error(userMessage(it))
        }
    }

    fun refresh() {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        viewModelScope.launch { _workspace.value = WorkspaceState.Loading("Refreshing project…"); loadProject(ws.project.uri, preserveTabs = true) }
    }

    fun openFile(file: ProjectFile) {
        if (file.isDirectory) return
        viewModelScope.launch {
            val ws = _workspace.value as? WorkspaceState.Ready ?: return@launch
            val existing = ws.tabs.firstOrNull { it.path == file.relativePath }
            val tab = existing ?: runCatching {
                val content = container.projects.read(file.uri)
                OpenTab(file.relativePath, file.uri, content, content, languageFor(file.extension))
            }.getOrElse {
                showStatus(userMessage(it))
                return@launch
            }
            val tabs = if (existing == null) ws.tabs + tab else ws.tabs
            _workspace.value = ws.copy(tabs = tabs, activeTab = file.relativePath)
            _destination.value = Destination.EDITOR
            runDiagnostics()
        }
    }

    fun selectTab(path: String) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        if (ws.tabs.any { it.path == path }) _workspace.value = ws.copy(activeTab = path)
    }

    fun closeTab(path: String) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        val tab = ws.tabs.firstOrNull { it.path == path } ?: return
        if (tab.isDirty) {
            showStatus("$path has unsaved changes. Save before closing it.")
            return
        }
        val remaining = ws.tabs.filterNot { it.path == path }
        closedTabs.removeAll { it.path == tab.path }
        closedTabs.addLast(tab)
        while (closedTabs.size > 20) closedTabs.removeFirst()
        val next = if (ws.activeTab == path) remaining.lastOrNull()?.path else ws.activeTab
        _workspace.value = ws.copy(tabs = remaining, activeTab = next)
    }

    fun closeOtherTabs(path: String) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        if (ws.tabs.any { it.path != path && it.isDirty }) { showStatus("Save other modified files before closing them."); return }
        ws.tabs.filter { it.path != path }.forEach { closedTabs.addLast(it) }
        while (closedTabs.size > 20) closedTabs.removeFirst()
        _workspace.value = ws.copy(tabs = listOfNotNull(ws.tabs.firstOrNull { it.path == path }), activeTab = path)
    }

    fun reopenLastTab() {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        val tab = closedTabs.removeLastOrNull() ?: run { showStatus("No closed tab to reopen"); return }
        if (ws.tabs.any { it.path == tab.path }) return
        _workspace.value = ws.copy(tabs = ws.tabs + tab, activeTab = tab.path)
        setDestination(Destination.EDITOR)
    }

    fun updateActiveContent(content: String, selectionStart: Int = content.length, selectionEnd: Int = selectionStart) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        val path = ws.activeTab ?: return
        val tabs = ws.tabs.map { if (it.path == path) it.copy(content = content, selectionStart = selectionStart, selectionEnd = selectionEnd) else it }
        _workspace.value = ws.copy(tabs = tabs)
        runDiagnostics()
        autosaveJob?.cancel()
        if (autosave.value) {
            val tab = tabs.firstOrNull { it.path == path } ?: return
            autosaveJob = viewModelScope.launch {
                delay(700)
                saveTab(tab.path, silent = true)
            }
        }
    }

    fun saveActive() {
        val path = (_workspace.value as? WorkspaceState.Ready)?.activeTab ?: return
        saveTab(path, silent = false)
    }

    private fun saveTab(path: String, silent: Boolean) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        val tab = ws.tabs.firstOrNull { it.path == path } ?: return
        viewModelScope.launch {
            runCatching { container.projects.write(tab.uri, tab.content) }
                .onSuccess {
                    val current = _workspace.value as? WorkspaceState.Ready ?: return@onSuccess
                    val tabs = current.tabs.map { if (it.path == path) it.copy(savedContent = tab.content) else it }
                    _workspace.value = current.copy(tabs = tabs)
                    if (!silent) showStatus("Saved $path")
                    runDiagnostics()
                }
                .onFailure { if (!silent) showStatus("Unable to save $path: ${userMessage(it)}") }
        }
    }

    fun closeAllTabs() {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        if (ws.tabs.any { it.isDirty }) { showStatus("Save modified files before closing all tabs."); return }
        _workspace.value = ws.copy(tabs = emptyList(), activeTab = null)
    }

    fun createFile(parent: Uri, name: String, initialContent: String = "") {
        viewModelScope.launch {
            runCatching {
                val uri = container.projects.createFile(parent, name, mimeFor(name))
                if (initialContent.isNotEmpty()) container.projects.write(uri, initialContent)
            }.onSuccess { showStatus("Created $name"); refresh() }.onFailure { showStatus(userMessage(it)) }
        }
    }

    fun createFolder(parent: Uri, name: String) {
        viewModelScope.launch {
            runCatching { container.projects.createDirectory(parent, name) }
                .onSuccess { showStatus("Created $name"); refresh() }
                .onFailure { showStatus(userMessage(it)) }
        }
    }

    fun rename(uri: Uri, newName: String) {
        viewModelScope.launch {
            runCatching { container.projects.rename(uri, newName) }
                .onSuccess { showStatus("Renamed successfully"); refresh() }
                .onFailure { showStatus(userMessage(it)) }
        }
    }

    fun copy(uri: Uri) { copiedUri = uri; showStatus("Copied to Coder Mobile clipboard") }

    fun paste(parent: Uri) {
        val source = copiedUri ?: run { showStatus("Nothing copied yet"); return }
        if (source == parent) { showStatus("A folder cannot be pasted into itself"); return }
        viewModelScope.launch {
            runCatching { container.projects.duplicate(source, parent) }
                .onSuccess { showStatus("Pasted copy"); refresh() }
                .onFailure { showStatus(userMessage(it)) }
        }
    }

    fun duplicate(file: ProjectFile) {
        val parent = parentUri(file) ?: return
        viewModelScope.launch {
            runCatching { container.projects.duplicate(file.uri, parent) }
                .onSuccess { showStatus("Duplicated ${file.name}"); refresh() }
                .onFailure { showStatus(userMessage(it)) }
        }
    }

    fun move(file: ProjectFile, target: Uri) {
        viewModelScope.launch {
            runCatching { container.projects.move(file.uri, target) }
                .onSuccess { showStatus("Moved ${file.name}"); refresh() }
                .onFailure { showStatus(userMessage(it)) }
        }
    }

    fun delete(file: ProjectFile) {
        viewModelScope.launch {
            runCatching { container.projects.delete(file.uri) }
                .onSuccess { showStatus("Deleted ${file.name}"); refresh() }
                .onFailure { showStatus(userMessage(it)) }
        }
    }

    fun importFile(source: Uri, parent: Uri) {
        viewModelScope.launch {
            runCatching { container.projects.importFile(source, parent) }
                .onSuccess { showStatus("Imported file"); refresh() }
                .onFailure { showStatus(userMessage(it)) }
        }
    }

    fun exportFile(source: Uri, destination: Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(source)?.use { input ->
                    getApplication<Application>().contentResolver.openOutputStream(destination, "wt")?.use { output -> input.copyTo(output) }
                        ?: error("Unable to write exported file")
                } ?: error("Unable to read source file")
            }.onSuccess { showStatus("File exported") }.onFailure { showStatus(userMessage(it)) }
        }
    }

    fun searchProject(query: String, caseSensitive: Boolean, wholeWord: Boolean, regex: Boolean, fileFilter: String) {
        _query.value = query
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        viewModelScope.launch {
            if (query.isBlank()) { _searchResults.value = emptyList(); return@launch }
            val results = mutableListOf<SearchResult>()
            val pattern = runCatching {
                val source = if (regex) query else Regex.escape(query)
                val bounded = if (wholeWord) "\\b$source\\b" else source
                Regex(bounded, if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
            }.getOrElse { showStatus("Invalid regular expression"); return@launch }
            for (file in ws.files.filter { !it.isDirectory && (fileFilter.isBlank() || it.name.endsWith(fileFilter, true)) }) {
                if (results.size >= 500) break
                val text = runCatching { container.projects.read(file.uri, 512_000) }.getOrNull() ?: continue
                text.split('\n').forEachIndexed { index, line ->
                    val match = pattern.find(line) ?: return@forEachIndexed
                    results += SearchResult(file, index + 1, match.range.first + 1, line.trim().take(240))
                }
            }
            _searchResults.value = results
        }
    }

    fun replaceInProject(search: String, replacement: String, replaceAll: Boolean, caseSensitive: Boolean, regex: Boolean) {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        viewModelScope.launch {
            val pattern = runCatching {
                val source = if (regex) search else Regex.escape(search)
                Regex(source, if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
            }.getOrElse { showStatus("Invalid regular expression"); return@launch }
            var changed = 0
            for (file in ws.files.filter { !it.isDirectory }) {
                val old = runCatching { container.projects.read(file.uri, 1_000_000) }.getOrNull() ?: continue
                val new = if (replaceAll) pattern.replace(old, replacement) else pattern.replaceFirst(old, replacement)
                if (new != old) { container.projects.write(file.uri, new); changed++ }
            }
            showStatus("Updated $changed file${if (changed == 1) "" else "s"}")
            refresh()
        }
    }

    fun clearProblems() { _problems.value = emptyList() }

    private fun runDiagnostics() {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        viewModelScope.launch {
            val diagnostics = mutableListOf<Diagnostic>()
            ws.tabs.forEach { tab -> diagnostics += diagnosticsFor(tab) }
            _problems.value = diagnostics.take(300)
        }
    }

    private fun diagnosticsFor(tab: OpenTab): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val text = tab.content
        val lines = text.split('\n')
        var braces = 0
        var brackets = 0
        var parentheses = 0
        var inString: Char? = null
        var escaped = false
        lines.forEachIndexed { index, line ->
            line.forEachIndexed { col, ch ->
                if (inString != null) {
                    if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == inString) inString = null
                } else when (ch) {
                    '\'', '"', '`' -> inString = ch
                    '{' -> braces++
                    '}' -> braces--
                    '[' -> brackets++
                    ']' -> brackets--
                    '(' -> parentheses++
                    ')' -> parentheses--
                }
                if (braces < 0 || brackets < 0 || parentheses < 0) {
                    out += Diagnostic(Severity.ERROR, tab.path, index + 1, col + 1, "Closing delimiter has no matching opening delimiter")
                    braces = maxOf(braces, 0); brackets = maxOf(brackets, 0); parentheses = maxOf(parentheses, 0)
                }
            }
            if (line.contains("TODO", true)) out += Diagnostic(Severity.INFO, tab.path, index + 1, maxOf(1, line.indexOf("TODO", ignoreCase = true) + 1), "TODO marker found")
        }
        if (inString != null) out += Diagnostic(Severity.WARNING, tab.path, lines.size, maxOf(1, lines.lastOrNull()?.length ?: 1), "String literal may be unterminated")
        if (braces != 0 || brackets != 0 || parentheses != 0) out += Diagnostic(Severity.WARNING, tab.path, lines.size, 1, "File may contain unmatched delimiters")
        if (tab.language == "json") runCatching { JSONObject(text) }.onFailure {
            runCatching { JSONArray(text) }.onFailure { out += Diagnostic(Severity.ERROR, tab.path, 1, 1, "Invalid JSON document") }
        }
        return out
    }

    fun parentUri(file: ProjectFile): Uri? {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return null
        val parentPath = file.relativePath.substringBeforeLast('/', "")
        return if (parentPath.isBlank()) ws.project.uri else ws.files.firstOrNull { it.isDirectory && it.relativePath == parentPath }?.uri
    }

    fun rootUri(): Uri? = (_workspace.value as? WorkspaceState.Ready)?.project?.uri

    fun setTheme(value: String) { viewModelScope.launch { container.projectStore.saveTheme(value) } }
    fun setAutosave(value: Boolean) { viewModelScope.launch { container.projectStore.saveAutosave(value) } }
    fun setFontSize(value: Long) { viewModelScope.launch { container.projectStore.saveFontSize(value) } }
    fun setTabSize(value: Int) { viewModelScope.launch { container.projectStore.saveTabSize(value) } }
    fun setWordWrap(value: Boolean) { viewModelScope.launch { container.projectStore.saveWordWrap(value) } }
    fun setDefaultProvider(id: String) { viewModelScope.launch { container.projectStore.saveDefaultProvider(id) } }
    fun removeRecent(uri: Uri) { viewModelScope.launch { container.projectStore.removeProject(uri) } }
    fun togglePinned(uri: Uri) { viewModelScope.launch { container.projectStore.togglePinned(uri) } }

    fun createProject(parentUri: Uri, name: String, template: String) {
        viewModelScope.launch {
            runCatching {
                val dirUri = container.projects.createDirectory(parentUri, name)
                val files = when (template) {
                    "HTML/CSS/JavaScript" -> listOf(
                        Triple("index.html", "text/html", "<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Coder Mobile</title><link rel=\"stylesheet\" href=\"style.css\"></head><body><main><h1>Hello from Coder Mobile</h1><p>Edit index.html and use Preview.</p></main><script src=\"app.js\"></script></body></html>\n"),
                        Triple("style.css", "text/css", "body{font-family:system-ui;margin:40px;line-height:1.5;background:#10141b;color:#f4f7ff}main{max-width:720px;margin:auto}p{color:#a9b3c2}\n"),
                        Triple("app.js", "text/javascript", "console.log('Hello from Coder Mobile');\n")
                    )
                    "Basic Web App" -> listOf(
                        Triple("index.html", "text/html", "<!doctype html>\n<html><body><div id=\"app\"></div><script src=\"app.js\"></script></body></html>\n"),
                        Triple("app.js", "text/javascript", "document.getElementById('app').textContent = 'Hello from Coder Mobile';\n")
                    )
                    "TypeScript" -> listOf(
                        Triple("index.ts", "text/plain", "const message: string = 'Hello from Coder Mobile';\nconsole.log(message);\n"),
                        Triple("tsconfig.json", "application/json", "{\"compilerOptions\":{\"target\":\"ES2022\",\"strict\":true}}\n")
                    )
                    "Node.js" -> listOf(
                        Triple("package.json", "application/json", "{\"name\":\"coder-mobile-project\",\"private\":true,\"scripts\":{\"start\":\"node index.js\"}}\n"),
                        Triple("index.js", "text/javascript", "console.log('Hello from Coder Mobile');\n")
                    )
                    "Empty Project" -> emptyList()
                    else -> listOf(Triple("README.md", "text/markdown", "# Coder Mobile Project\n\nCreated on Android.\n"))
                }
                files.forEach { (filename, mime, content) ->
                    val uri = container.projects.createFile(dirUri, filename, mime)
                    container.projects.write(uri, content)
                }
                openProject(dirUri)
            }.onFailure { _workspace.value = WorkspaceState.Error(userMessage(it)) }
        }
    }

    fun saveProvider(config: AiProviderConfig) { viewModelScope.launch { container.providers.save(config); showStatus("Provider saved securely") } }
    fun removeProvider(config: AiProviderConfig) { viewModelScope.launch { container.providers.remove(config); showStatus("Provider removed") } }
    suspend fun testProvider(config: AiProviderConfig): Result<String> = container.ai.test(config)

    suspend fun askAi(config: AiProviderConfig, prompt: String, scope: AiContextScope): Result<String> {
        return container.ai.complete(config, systemPrompt(scope), buildAiContext(scope) + "\n\nUser request:\n" + prompt)
    }

    suspend fun proposeCurrentFileChange(config: AiProviderConfig, instruction: String): Result<String> {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return Result.failure(IllegalStateException("Open a file first"))
        val active = ws.tabs.firstOrNull { it.path == ws.activeTab } ?: return Result.failure(IllegalStateException("Open a file first"))
        val prompt = "Return only one fenced code block containing the complete replacement content for this file. Do not omit unchanged lines. File: ${active.path}\nLanguage: ${active.language}\n\nCurrent content:\n${active.content.take(30_000)}\n\nRequested change:\n$instruction"
        return container.ai.complete(config, systemPrompt(AiContextScope.CURRENT_FILE), prompt).map { response ->
            val code = extractCodeBlock(response) ?: response.trim()
            _proposal.value = AiChangeProposal(active.path, code, active.content, "Review AI replacement for ${active.path}")
            "AI proposed a replacement for ${active.path}. Review it before applying."
        }
    }

    suspend fun proposeNewFile(config: AiProviderConfig, path: String, instruction: String): Result<String> {
        require(path.trim().isNotBlank()) { "File path is required" }
        val prompt = "Return only one fenced code block containing the complete content for a new file. Path: ${path.trim()}\n\nCreate this file according to:\n$instruction"
        return container.ai.complete(config, systemPrompt(AiContextScope.PROJECT), prompt).map { response ->
            val code = extractCodeBlock(response) ?: response.trim()
            _proposal.value = AiChangeProposal(path.trim(), code, "", "Review AI-created file ${path.trim()}")
            "AI proposed a new file. Review it before creating it."
        }
    }

    fun applyProposal() {
        val proposal = _proposal.value ?: return
        viewModelScope.launch {
            runCatching {
                val ws = _workspace.value as? WorkspaceState.Ready ?: error("Project is not open")
                val existing = ws.files.firstOrNull { it.relativePath == proposal.path }
                val uri = existing?.uri ?: run {
                    val segments = proposal.path.split('/').filter { it.isNotBlank() }
                    require(segments.isNotEmpty()) { "File path is required" }
                    var parent = ws.project.uri
                    var path = ""
                    segments.dropLast(1).forEach { segment ->
                        path = if (path.isBlank()) segment else "$path/$segment"
                        parent = ws.files.firstOrNull { it.isDirectory && it.relativePath == path }?.uri
                            ?: error("Folder '$path' was not found")
                    }
                    container.projects.createFile(parent, segments.last(), mimeFor(segments.last()))
                }
                container.projects.write(uri, proposal.content)
                _proposal.value = null
                showStatus("Applied AI change to ${proposal.path}")
                loadProject(ws.project.uri, preserveTabs = true)
                if (existing == null) {
                    val file = (_workspace.value as? WorkspaceState.Ready)?.files?.firstOrNull { it.relativePath == proposal.path }
                    if (file != null) openFile(file)
                }
            }.onFailure { showStatus("Could not apply AI change: ${userMessage(it)}") }
        }
    }

    fun rejectProposal() { _proposal.value = null; showStatus("AI change rejected") }

    suspend fun readPreviewText(file: ProjectFile): String = container.projects.read(file.uri, 2_000_000)

    fun gitInfo(): Pair<Boolean, String> {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return false to "No project is open"
        val gitDir = container.projects.root(ws.project.uri)?.findFile(".git")
        if (gitDir == null) return false to "This project does not contain a .git directory."
        return true to "Git repository detected. Branch and working-tree operations are available only when a Git engine is present on the device."
    }

    fun runProject() {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        when (ws.project.type) {
            ProjectType.WEB -> setDestination(Destination.PREVIEW)
            else -> showStatus("No on-device runtime is available for ${ws.project.type.label}. Review the detected build configuration in Run & Build.")
        }
    }

    fun buildProject() {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return
        val command = when (ws.project.type) {
            ProjectType.ANDROID -> "Gradle project detected · assembleDebug"
            ProjectType.NODE, ProjectType.TYPESCRIPT -> "Node project detected · npm run build/start when a compatible runtime is available"
            ProjectType.WEB -> "Static web project detected · Preview is available"
            ProjectType.PYTHON -> "Python project detected · local Python runtime is not bundled"
            ProjectType.RUST -> "Rust project detected · Rust toolchain is not bundled"
            ProjectType.GENERIC -> "No supported build system detected"
        }
        showStatus(command)
    }

    suspend fun buildHtmlPreview(file: ProjectFile): String {
        val ws = _workspace.value as? WorkspaceState.Ready
        val openTab = ws?.tabs?.firstOrNull { it.path == file.relativePath }
        val active = openTab?.content ?: container.projects.read(file.uri, 2_000_000)
        if (ws == null) return active
        var html = active

        // Load linked local assets before entering Regex.replace lambdas; those
        // lambdas are not suspendable, while ProjectRepository.read() is.
        val linkedContents = mutableMapOf<String, String>()
        for (candidate in ws.files.filter { !it.isDirectory && it.size <= 300_000 }) {
            val content = try {
                container.projects.read(candidate.uri, 300_000)
            } catch (_: Exception) {
                null
            }
            if (content != null) linkedContents[candidate.relativePath] = content
        }

        val cssRegex = Regex("<link[^>]+href=[\\"']([^\\"']+)[\\"][^>]*>", RegexOption.IGNORE_CASE)
        html = cssRegex.replace(html) { match ->
            val path = resolveSibling(file.relativePath, match.groupValues[1])
            val linked = linkedContents[path]
            if (linked != null) "<style>${escapeForStyle(linked)}</style>" else match.value
        }
        val scriptRegex = Regex("<script([^>]*)src=[\\"']([^\\"']+)[\\"][^>]*)></script>", RegexOption.IGNORE_CASE)
        html = scriptRegex.replace(html) { match ->
            val path = resolveSibling(file.relativePath, match.groupValues[2])
            val linked = linkedContents[path]
            if (linked != null) "<script${match.groupValues[1]}${match.groupValues[3]}>${escapeForScript(linked)}</script>" else match.value
        }
        return html
    }

    private suspend fun buildAiContext(scope: AiContextScope): String {
        val ws = _workspace.value as? WorkspaceState.Ready ?: return "No project is open."
        val active = ws.tabs.firstOrNull { it.path == ws.activeTab }
        val context = StringBuilder()
        context.append("Project: ${ws.project.displayName} (${ws.project.type.label})\n")
        context.append("Project files: ${ws.files.count { !it.isDirectory }} files, ${ws.files.count { it.isDirectory }} folders\n")
        context.append("File tree:\n")
        for (file in ws.files.take(250)) {
            context.append(if (file.isDirectory) "[DIR] " else "[FILE] ")
                .append(file.relativePath)
                .append('\n')
        }
        when (scope) {
            AiContextScope.CURRENT_FILE -> if (active != null) {
                context.append("\nActive file ${active.path}:\n${active.content.take(20_000)}")
            }
            AiContextScope.OPEN_FILES -> {
                for (tab in ws.tabs) {
                    context.append("\n--- ${tab.path} ---\n${tab.content.take(10_000)}")
                }
            }
            AiContextScope.PROJECT -> {
                for (file in ws.files.filter { !it.isDirectory && it.size < 80_000 }.take(16)) {
                    val content = try {
                        container.projects.read(file.uri, 80_000)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    context.append("\n--- ${file.relativePath} ---\n${content.take(6_000)}")
                }
            }
        }
        return context.toString()
    }

    private fun systemPrompt(scope: AiContextScope) = "You are the embedded coding agent inside Coder Mobile. Be accurate, concise, and project-aware. Current context scope: ${scope.name}. Do not claim to execute runtimes unavailable on Android. Never request or reveal API keys. Prefer minimal, reviewable changes."

    private fun extractCodeBlock(response: String): String? = Regex("```(?:[a-zA-Z0-9_+-]+)?\\s*([\\s\\S]*?)```").find(response)?.groupValues?.getOrNull(1)?.trimEnd()

    private fun resolveSibling(filePath: String, relative: String): String {
        val base = filePath.substringBeforeLast('/', "")
        val parts = (if (base.isBlank()) relative else "$base/$relative").split('/').toMutableList()
        val normalized = ArrayDeque<String>()
        parts.forEach { part -> when (part) { "" , "." -> Unit; ".." -> if (normalized.isNotEmpty()) normalized.removeLast(); else -> normalized.addLast(part) } }
        return normalized.joinToString("/")
    }

    private fun escapeForStyle(content: String) = content.replace("</style>", "<\\/style>", true)
    private fun escapeForScript(content: String) = content.replace("</script>", "<\\/script>", true)

    private fun showStatus(message: String) { _status.value = message }

    private fun userMessage(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."

    private fun languageFor(ext: String) = when (ext) {
        "kt", "kts" -> "kotlin"
        "ts", "tsx" -> "typescript"
        "js", "jsx", "mjs", "cjs" -> "javascript"
        "html", "htm" -> "html"
        "css", "scss" -> "css"
        "py" -> "python"
        "rs" -> "rust"
        "json" -> "json"
        "xml" -> "xml"
        "md", "markdown" -> "markdown"
        else -> "text"
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
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
