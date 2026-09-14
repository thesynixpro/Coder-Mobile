package com.aprax.coderm.ui.screens

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aprax.coderm.data.*
import com.aprax.coderm.ui.components.*
import com.aprax.coderm.ui.editor.CodeEditor

private data class CreateRequest(val name: String, val template: String)

@Composable
fun RootScreen(vm: MainViewModel, context: Context) {
    val workspace by vm.workspace.collectAsState()
    val destination by vm.destination.collectAsState()
    val theme by vm.theme.collectAsState()
    val recent by vm.recentProjects.collectAsState()
    var pendingCreate by remember { mutableStateOf<CreateRequest?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val request = pendingCreate
        pendingCreate = null
        if (request == null) vm.openProject(uri) else vm.createProject(uri, request.name, request.template)
    }

    if (workspace is WorkspaceState.NoProject) {
        OnboardingScreen(
            recent = recent,
            onOpen = { directoryPicker.launch(null) },
            onCreate = { showCreateDialog = true },
            onRecent = { vm.openProject(it.uri) }
        )
        if (showCreateDialog) {
            CreateProjectDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, template -> showCreateDialog = false; pendingCreate = CreateRequest(name, template); directoryPicker.launch(null) }
            )
        }
        return
    }

    val ready = workspace as? WorkspaceState.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ready?.project?.displayName ?: "Coder Mobile", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ready?.let { Text(it.project.type.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.setDestination(Destination.EXPLORER) }) { Icon(Icons.Default.Code, "Coder Mobile") } },
                actions = {
                    ready?.let {
                        IconButton(onClick = { vm.saveActive() }) { Icon(Icons.Default.Save, "Save") }
                        IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavItem("Explorer", Icons.Default.FolderOpen, destination == Destination.EXPLORER) { vm.setDestination(Destination.EXPLORER) }
                NavItem("Editor", Icons.Default.Code, destination == Destination.EDITOR) { vm.setDestination(Destination.EDITOR) }
                NavItem("Preview", Icons.Default.Visibility, destination == Destination.PREVIEW) { vm.setDestination(Destination.PREVIEW) }
                NavItem("AI", Icons.Default.AutoAwesome, destination == Destination.AI) { vm.setDestination(Destination.AI) }
                NavItem("More", Icons.Default.MoreHoriz, destination == Destination.SETTINGS) { vm.setDestination(Destination.SETTINGS) }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (destination) {
                Destination.EXPLORER -> ready?.let { ExplorerScreen(it, vm) }
                Destination.EDITOR -> ready?.let { EditorScreen(it, vm) }
                Destination.PREVIEW -> ready?.let { PreviewScreen(it) }
                Destination.AI -> ready?.let { AiScreen(it, vm) }
                Destination.TERMINAL -> TerminalScreen(ready)
                Destination.PROBLEMS -> ProblemsScreen(vm)
                Destination.SEARCH -> SearchScreen(ready, vm)
                Destination.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable
private fun OnboardingScreen(recent: List<ProjectRef>, onOpen: () -> Unit, onCreate: () -> Unit, onRecent: (ProjectRef) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp).systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f)); BrandMark(); Spacer(Modifier.height(20.dp))
        Text("Coder Mobile", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp)); Text("Code. Build. Preview. Anywhere.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text("Open Project") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(10.dp)); Text("Create Project") }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp)); Text("Recent Projects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
            recent.take(4).forEach { project ->
                ListItem(headlineContent = { Text(project.displayName) }, supportingContent = { Text(project.type.label) }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { onRecent(project) })
            }
        }
        Spacer(Modifier.weight(1f)); Text("The selected project directory remains the source of truth.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("MyProject") }
    var template by remember { mutableStateOf("Empty Project") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = { onCreate(name.trim().ifBlank { "MyProject" }, template) }) { Text("Choose Location") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Create Project") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp)); Text("Template", style = MaterialTheme.typography.labelLarge)
                listOf("Empty Project", "HTML/CSS/JavaScript", "Basic Web App", "TypeScript", "Node.js").forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { template = t }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(template == t, { template = t }); Spacer(Modifier.width(8.dp)); Text(t) }
                }
            }
        }
    )
}

@Composable
private fun ExplorerScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Explorer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.setDestination(Destination.SEARCH) }) { Icon(Icons.Default.Search, "Search") }
        }
        Surface(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(ws.project.displayName, modifier = Modifier.weight(1f)); AssistChip(onClick = {}, label = { Text(ws.project.type.label) }) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query, { query = it }, modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.FilterList, null) }, placeholder = { Text("Filter files") })
        val visible = remember(ws.files, query) { ws.files.filter { query.isBlank() || it.relativePath.contains(query, true) } }
        if (visible.isEmpty()) EmptyState(Icons.Default.FolderOff, "No files found", "Try another filter or add files in the selected project directory.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            items(visible, key = { it.id }) { file ->
                ListItem(
                    headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (file.relativePath != file.name) Text(file.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description, null) },
                    trailingContent = { if (!file.isDirectory) Text(file.extension.uppercase(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.clickable { if (!file.isDirectory) vm.openFile(file) }
                )
            }
        }
    }
}

@Composable
private fun EditorScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    val active = ws.tabs.firstOrNull { it.path == ws.activeTab }
    if (active == null) { EmptyState(Icons.Default.Code, "No file open", "Open a source file from Explorer to start coding.") { vm.setDestination(Destination.EXPLORER) }; return }
    val font by vm.fontSize.collectAsState()
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0D12))) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
            ws.tabs.forEach { tab ->
                Surface(shape = MaterialTheme.shapes.small, color = if (tab.path == active.path) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, modifier = Modifier.padding(end = 6.dp).clickable { vm.openFile(ws.files.first { it.relativePath == tab.path }) }) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Text(tab.path.substringAfterLast('/'), fontSize = 13.sp, fontFamily = FontFamily.Monospace); if (tab.isDirty) Text(" •", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        CodeEditor(active.content, active.language, font.toInt(), vm::updateActiveContent, Modifier.weight(1f))
        CodingToolbar()
    }
}

@Composable
private fun CodingToolbar() {
    val keys = listOf("{", "}", "(", ")", "[", "]", "<", ">", "/", "=", ":", ";", "'", "\"", ".", ",", "_", "-", "+", "*", "Tab", "Ctrl", "Alt", "Esc", "←", "→", "↑", "↓")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp)) { keys.forEach { key -> AssistChip(onClick = {}, label = { Text(key, fontFamily = FontFamily.Monospace) }, modifier = Modifier.padding(end = 5.dp)) } }
}

@Composable
private fun PreviewScreen(ws: WorkspaceState.Ready) {
    val active = ws.tabs.firstOrNull { it.path == ws.activeTab }
    if (active == null) { EmptyState(Icons.Default.VisibilityOff, "No preview", "Open a previewable file first."); return }
    if (active.language != "html") { EmptyState(Icons.Default.VisibilityOff, "No executable preview", "This MVP only executes supported HTML previews; it does not pretend unavailable toolchains exist."); return }
    AndroidView(factory = { ctx -> WebView(ctx).apply {
        webViewClient = WebViewClient()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
    } }, update = { it.loadDataWithBaseURL("https://coder.local/", active.content, "text/html", "utf-8", null) }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun TerminalScreen(ws: WorkspaceState.Ready?) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Coder Mobile terminal\n\nProject-root execution is capability-gated in this build.\nCommands are not executed from the UI.\n") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Terminal", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(10.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), color = Color(0xFF090C10), shape = MaterialTheme.shapes.large) { Text(output, modifier = Modifier.padding(14.dp), color = Color(0xFFE4E8EF), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("command preview") })
            IconButton(onClick = { if (input.isNotBlank()) { output += "\n$ ${input.trim()}\nNot executed: terminal execution is disabled in this build.\n"; input = "" } }) { Icon(Icons.Default.PlayArrow, "Enter") }
        }
    }
}

@Composable
private fun SearchScreen(ws: WorkspaceState.Ready?, vm: MainViewModel) {
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<ProjectFile>>(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; results = ws?.files?.filter { f -> !f.isDirectory && f.name.contains(it, true) }.orEmpty() }, modifier = Modifier.weight(1f), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search files") })
            IconButton(onClick = { vm.setDestination(Destination.EXPLORER) }) { Icon(Icons.Default.Close, "Close") }
        }
        if (query.isBlank()) EmptyState(Icons.Default.Search, "Project search", "Find files quickly from the selected workspace.")
        else if (results.isEmpty()) EmptyState(Icons.Default.SearchOff, "No results", "No files matched “$query”.")
        else LazyColumn { items(results) { file -> ListItem(headlineContent = { Text(file.name) }, supportingContent = { Text(file.relativePath) }, leadingContent = { Icon(Icons.Default.Description, null) }, modifier = Modifier.clickable { vm.openFile(file) }) } }
    }
}

@Composable
private fun ProblemsScreen(vm: MainViewModel) {
    val problems by vm.problems.collectAsState()
    if (problems.isEmpty()) EmptyState(Icons.Default.CheckCircle, "No problems", "Diagnostics produced by supported tooling will appear here.")
    else LazyColumn { items(problems) { p -> ListItem(headlineContent = { Text(p.message) }, supportingContent = { Text("${p.filePath}:${p.line}:${p.column}") }, leadingContent = { Icon(if (p.severity == Severity.ERROR) Icons.Default.Error else Icons.Default.Warning, null) }) } }
}

@Composable
private fun AiScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    val providers by vm.providers.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("Configure a provider in Settings → AI Providers. Context is limited to the active file and workspace metadata.") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(providers) { if (selectedId == null) selectedId = providers.firstOrNull()?.id }
    val selected = providers.firstOrNull { it.id == selectedId }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("AI Assistant", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); AssistChip(onClick = {}, label = { Text("Project-aware") }) }
        if (providers.isNotEmpty()) { Spacer(Modifier.height(8.dp)); SingleChoiceSegmentedButtonRow { providers.take(3).forEachIndexed { index, provider -> SegmentedButton(selected = provider.id == selectedId, onClick = { selectedId = provider.id }, shape = SegmentedButtonDefaults.itemShape(index, providers.take(3).size)) { Text(provider.name) } } } }
        Spacer(Modifier.height(12.dp)); Surface(Modifier.weight(1f).fillMaxWidth(), tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) { Text(response, modifier = Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp)); OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("Ask about this file or propose a change…") })
        Spacer(Modifier.height(8.dp)); Button(enabled = selected != null && prompt.isNotBlank() && !busy, onClick = {
            val provider = selected ?: return@Button; busy = true; response = "Generating…"
            scope.launch { val result = vm.askAi(provider, prompt); response = result.getOrElse { "AI request failed: ${it.message}" }; busy = false }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Working…" else "Send") }
        Spacer(Modifier.height(6.dp)); Text("AI edits are advisory in this build. Do not silently apply destructive project-wide changes.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val theme by vm.theme.collectAsState(); val autosave by vm.autosave.collectAsState(); val font by vm.fontSize.collectAsState(); val providers by vm.providers.collectAsState()
    var showProviders by remember { mutableStateOf(false) }
    if (showProviders) { AiProviderSettings(vm, providers, onDismiss = { showProviders = false }); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { SectionHeader("General", Icons.Default.Tune) }
        item { SettingsRow("Appearance", "Theme: $theme", Icons.Default.DarkMode) { vm.setTheme(if (theme == "system") "dark" else if (theme == "dark") "light" else "system") } }
        item { ListItem(headlineContent = { Text("Autosave") }, supportingContent = { Text("Save edits after changes") }, trailingContent = { Switch(autosave, vm::setAutosave) }) }
        item { SectionHeader("Editor", Icons.Default.Code) }
        item { SettingsRow("Font size", "${font}sp", Icons.Default.TextFields) { vm.setFontSize((font + 1).coerceAtMost(24)) } }
        item { SectionHeader("AI", Icons.Default.AutoAwesome) }
        item { SettingsRow("AI Providers", "${providers.size} configured", Icons.Default.Key) { showProviders = true } }
        item { SectionHeader("Workspace", Icons.Default.Folder) }
        item { SettingsRow("Search", "Project file search", Icons.Default.Search) { vm.setDestination(Destination.SEARCH) } }
        item { SettingsRow("Problems", "Diagnostics and Explain & Fix entry point", Icons.Default.ErrorOutline) { vm.setDestination(Destination.PROBLEMS) } }
        item { SettingsRow("Terminal", "Capability-gated shell surface", Icons.Default.Terminal) { vm.setDestination(Destination.TERMINAL) } }
        item { SectionHeader("Security", Icons.Default.Security) }
        item { ListItem(headlineContent = { Text("API keys") }, supportingContent = { Text("Encrypted at rest; never intentionally logged or displayed in full") }) }
        item { SectionHeader("About", Icons.Default.Info) }
        item { ListItem(headlineContent = { Text("Coder Mobile") }, supportingContent = { Text("1.0.0 · Kotlin · Jetpack Compose · com.aprax.coderm") }) }
    }
}

@Composable
private fun AiProviderSettings(vm: MainViewModel, providers: List<AiProviderConfig>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(providers.firstOrNull()?.name ?: "OpenAI") }
    var base by remember { mutableStateOf(providers.firstOrNull()?.baseUrl ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(providers.firstOrNull()?.model ?: "gpt-4.1-mini") }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !testing, onClick = {
                    testing = true; status = "Testing…"
                    scope.launch {
                        val cfg = AiProviderConfig("custom-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}", name, base, model, key)
                        val result = vm.testProvider(cfg); status = result.getOrElse { it.message ?: "Unknown error" }; testing = false
                    }
                }) { Text(if (testing) "Testing" else "Test Connection") }
                Button(onClick = { vm.saveProvider(AiProviderConfig("custom-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}", name, base, model, key)); status = "Saved securely" }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("AI Providers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Custom OpenAI-compatible provider", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Provider Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Spacer(Modifier.height(8.dp))
                Text("Example base URL: https://example.com/v1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Saved keys are encrypted locally and are not shown in full after saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    )
}
