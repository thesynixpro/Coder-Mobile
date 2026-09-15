package com.aprax.coderm.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aprax.coderm.data.*
import com.aprax.coderm.ui.components.*
import com.aprax.coderm.ui.editor.CodeEditor
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class CreateRequest(val name: String, val template: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(vm: MainViewModel, context: Context) {
    val workspace by vm.workspace.collectAsState()
    val destination by vm.destination.collectAsState()
    val recent by vm.recentProjects.collectAsState()
    val status by vm.status.collectAsState()
    val proposal by vm.proposal.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingCreate by rememberSaveable { mutableStateOf<CreateRequest?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val request = pendingCreate
        pendingCreate = null
        if (request == null) vm.openProject(uri) else vm.createProject(uri, request.name, request.template)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val root = vm.rootUri() ?: return@rememberLauncherForActivityResult
        uris.forEach { vm.importFile(it, root) }
    }
    var exportSource by remember { mutableStateOf<Uri?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destinationUri ->
        val source = exportSource
        exportSource = null
        if (source != null && destinationUri != null) vm.exportFile(source, destinationUri)
    }

    LaunchedEffect(status) {
        status?.let { snackbarHostState.showSnackbar(it); vm.clearStatus() }
    }

    when (val state = workspace) {
        WorkspaceState.NoProject -> {
            OnboardingScreen(
                recent = recent,
                onOpen = { directoryPicker.launch(null) },
                onCreate = { showCreateDialog = true },
                onRecent = { vm.openProject(it.uri) },
                onRemoveRecent = vm::removeRecent,
                onPinRecent = vm::togglePinned
            )
            if (showCreateDialog) CreateProjectDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, template ->
                    showCreateDialog = false
                    pendingCreate = CreateRequest(name, template)
                    directoryPicker.launch(null)
                }
            )
            return
        }
        is WorkspaceState.Loading -> { WorkspaceStatusScreen("Opening workspace", state.message); return }
        is WorkspaceState.Error -> {
            WorkspaceStatusScreen("Workspace unavailable", state.message, "Open Project") { directoryPicker.launch(null) }
            return
        }
        is WorkspaceState.Ready -> Unit
    }

    val ready = workspace as WorkspaceState.Ready
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ready.project.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ready.project.type.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.setDestination(Destination.EXPLORER) }) { BrandMark(Modifier.size(28.dp)) } },
                actions = {
                    IconButton(onClick = vm::saveActive) { Icon(Icons.Default.Save, "Save") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh project") }
                    IconButton(onClick = { vm.setDestination(Destination.SEARCH) }) { Icon(Icons.Default.Search, "Search project") }
                }
            )
        },
        bottomBar = {
            if (!isTablet) {
                NavigationBar {
                    NavItem("Explorer", Icons.Default.FolderOpen, destination == Destination.EXPLORER) { vm.setDestination(Destination.EXPLORER) }
                    NavItem("Editor", Icons.Default.Code, destination == Destination.EDITOR) { vm.setDestination(Destination.EDITOR) }
                    NavItem("Preview", Icons.Default.Visibility, destination == Destination.PREVIEW) { vm.setDestination(Destination.PREVIEW) }
                    NavItem("AI", Icons.Default.AutoAwesome, destination == Destination.AI) { vm.setDestination(Destination.AI) }
                    NavItem("More", Icons.Default.MoreHoriz, destination == Destination.SETTINGS) { vm.setDestination(Destination.SETTINGS) }
                }
            }
        }
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            if (isTablet) {
                Surface(Modifier.width(190.dp).fillMaxHeight(), tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TabletNavItem("Explorer", Icons.Default.FolderOpen, destination == Destination.EXPLORER) { vm.setDestination(Destination.EXPLORER) }
                        TabletNavItem("Editor", Icons.Default.Code, destination == Destination.EDITOR) { vm.setDestination(Destination.EDITOR) }
                        TabletNavItem("Preview", Icons.Default.Visibility, destination == Destination.PREVIEW) { vm.setDestination(Destination.PREVIEW) }
                        TabletNavItem("AI", Icons.Default.AutoAwesome, destination == Destination.AI) { vm.setDestination(Destination.AI) }
                        TabletNavItem("Run & Build", Icons.Default.PlayArrow, destination == Destination.BUILD) { vm.setDestination(Destination.BUILD) }
                        TabletNavItem("Problems", Icons.Default.BugReport, destination == Destination.PROBLEMS) { vm.setDestination(Destination.PROBLEMS) }
                        TabletNavItem("Terminal", Icons.Default.Terminal, destination == Destination.TERMINAL) { vm.setDestination(Destination.TERMINAL) }
                        TabletNavItem("Git", Icons.Default.AccountTree, destination == Destination.GIT) { vm.setDestination(Destination.GIT) }
                        TabletNavItem("Settings", Icons.Default.Settings, destination == Destination.SETTINGS) { vm.setDestination(Destination.SETTINGS) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            when (destination) {
                Destination.EXPLORER -> ExplorerScreen(ready, vm, onImport = { importPicker.launch(arrayOf("*/*")) }, onExport = { source -> exportSource = source; exportPicker.launch(source.lastPathSegment ?: "export") }, onShare = { file -> shareProjectFile(context, file) })
                Destination.EDITOR -> EditorScreen(ready, vm)
                Destination.PREVIEW -> PreviewScreen(ready, vm, context)
                Destination.AI -> AiScreen(ready, vm)
                Destination.TERMINAL -> TerminalScreen(ready, vm)
                Destination.PROBLEMS -> ProblemsScreen(vm)
                Destination.SEARCH -> SearchScreen(ready, vm)
                Destination.SETTINGS -> SettingsScreen(vm, context)
                Destination.BUILD -> BuildScreen(ready, vm)
                Destination.GIT -> GitScreen(ready, vm)
            }
            }
        }
    }

    proposal?.let { current ->
        AlertDialog(
            onDismissRequest = vm::rejectProposal,
            title = { Text(if (current.original.isBlank()) "AI wants to create ${current.path}" else "Review AI change") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(current.summary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                        SelectionContainer { Text(current.content, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = { Button(onClick = vm::applyProposal) { Text(if (current.original.isBlank()) "Create" else "Apply") } },
            dismissButton = { TextButton(onClick = vm::rejectProposal) { Text("Reject") } }
        )
    }
}

@Composable
private fun WorkspaceStatusScreen(title: String, message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(Modifier.size(76.dp))
                Spacer(Modifier.height(18.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                actionLabel?.let {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onAction) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(it) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().selectable(selected = selected, onClick = onClick, role = Role.Tab).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(64.dp, 32.dp).clip(MaterialTheme.shapes.large).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun TabletNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).selectable(selected = selected, onClick = onClick, role = Role.Tab),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp)); Text(label)
        }
    }
}

@Composable
private fun OnboardingScreen(
    recent: List<ProjectRef>,
    onOpen: () -> Unit,
    onCreate: () -> Unit,
    onRecent: (ProjectRef) -> Unit,
    onRemoveRecent: (Uri) -> Unit,
    onPinRecent: (Uri) -> Unit
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { BrandMark(Modifier.size(52.dp)); Spacer(Modifier.width(14.dp)); Column { Text("Coder Mobile", style = MaterialTheme.typography.headlineMedium); Text("Code • Build • Create", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            Spacer(Modifier.height(42.dp))
            Text("Your mobile development workspace", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))
            Text("Select a project directory to start coding. Your selected folder remains the local source of truth.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text("Open Project") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(10.dp)); Text("Create Project") }
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(34.dp)); SectionHeader("Recent Projects", Icons.Default.History)
                recent.take(8).forEach { project ->
                    RecentProjectItem(project, onRecent, onRemoveRecent, onPinRecent)
                }
            } else {
                Spacer(Modifier.height(34.dp)); EmptyState(Icons.Default.FolderOpen, "No recent projects", "Open or create a directory to begin your first workspace.", "Open Project", onOpen)
            }
            Spacer(Modifier.height(30.dp))
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Security, null); Spacer(Modifier.width(10.dp)); Text("Projects stay on your device unless you explicitly use AI, Git/network actions, or share a file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun RecentProjectItem(project: ProjectRef, onOpen: (ProjectRef) -> Unit, onRemove: (Uri) -> Unit, onPin: (Uri) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).combinedClickable(onClick = { onOpen(project) }, onLongClick = { menu = true }), shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(project.type.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (project.pinned) Icon(Icons.Default.PushPin, "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Project actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(if (project.pinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin(project.uri) }, leadingIcon = { Icon(Icons.Default.PushPin, null) })
                    DropdownMenuItem({ Text("Remove from recent") }, onClick = { menu = false; onRemove(project.uri) }, leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) })
                }
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("My Project") }
    var template by rememberSaveable { mutableStateOf("Empty Project") }
    val templates = listOf("Empty Project", "HTML/CSS/JavaScript", "Basic Web App", "TypeScript", "Node.js")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp)); Text("Template", style = MaterialTheme.typography.labelLarge)
                templates.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = template == item, role = Role.RadioButton, onClick = { template = item })
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = template == item, onClick = { template = item })
                        Text(item)
                    }
                }
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), template) }) { Text("Choose location") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExplorerScreen(
    ws: WorkspaceState.Ready,
    vm: MainViewModel,
    onImport: () -> Unit,
    onExport: (Uri) -> Unit,
    onShare: (ProjectFile) -> Unit
) {
    val folders = remember(ws.files) { ws.files.filter { it.isDirectory } }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showCreateFile by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var parentForCreate by remember { mutableStateOf(ws.project.uri) }
    var menuFile by remember { mutableStateOf<ProjectFile?>(null) }
    var renameFile by remember { mutableStateOf<ProjectFile?>(null) }
    var deleteFile by remember { mutableStateOf<ProjectFile?>(null) }
    var moveFile by remember { mutableStateOf<ProjectFile?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Explorer", style = MaterialTheme.typography.titleLarge); Text("${ws.files.count { !it.isDirectory }} files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { parentForCreate = ws.project.uri; showCreateFile = true }) { Icon(Icons.Default.NoteAdd, "New file") }
            IconButton(onClick = { parentForCreate = ws.project.uri; showCreateFolder = true }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
            IconButton(onClick = { vm.paste(ws.project.uri) }) { Icon(Icons.Default.ContentPaste, "Paste") }
            IconButton(onClick = onImport) { Icon(Icons.Default.FileUpload, "Import") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(ws.project.displayName); Text(ws.project.type.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = { vm.setDestination(Destination.PROBLEMS) }) { Icon(Icons.Default.BugReport, "Problems") } }
                }
            }
            items(ws.files.filter { visible(it, expanded) }, key = { it.id }) { file ->
                val depth = file.relativePath.count { it == '/' }
                FileTreeRow(file, depth, expanded.contains(file.relativePath), onToggle = { expanded = if (expanded.contains(file.relativePath)) expanded - file.relativePath else expanded + file.relativePath }, onOpen = { if (file.isDirectory) { expanded = if (expanded.contains(file.relativePath)) expanded - file.relativePath else expanded + file.relativePath } else vm.openFile(file) }, onMenu = { menuFile = file })
            }
        }
    }

    menuFile?.let { file ->
        FileActionsDialog(file, onDismiss = { menuFile = null }, onOpen = { menuFile = null; if (!file.isDirectory) vm.openFile(file) }, onCreateFile = { parentForCreate = file.uri; showCreateFile = true; menuFile = null }, onCreateFolder = { parentForCreate = file.uri; showCreateFolder = true; menuFile = null }, onPaste = { vm.paste(file.uri); menuFile = null }, onRename = { renameFile = file; menuFile = null }, onDuplicate = { vm.duplicate(file); menuFile = null }, onCopy = { vm.copy(file.uri); menuFile = null }, onDelete = { deleteFile = file; menuFile = null }, onMove = { moveFile = file; menuFile = null }, onExport = { onExport(file.uri); menuFile = null }, onShare = { onShare(file); menuFile = null })
    }
    renameFile?.let { file -> RenameDialog(file.name, { renameFile = null }) { vm.rename(file.uri, it); renameFile = null } }
    deleteFile?.let { file -> ConfirmDeleteDialog(file.name, { deleteFile = null }) { vm.delete(file); deleteFile = null } }
    moveFile?.let { file -> MoveFileDialog(ws.project.displayName, ws.project.uri, folders, file, onDismiss = { moveFile = null }) { target -> vm.move(file, target); moveFile = null } }
    if (showCreateFile) SimpleNameDialog("Create file", "index.html", { showCreateFile = false }) { vm.createFile(parentForCreate, it); showCreateFile = false }
    if (showCreateFolder) SimpleNameDialog("Create folder", "src", { showCreateFolder = false }) { vm.createFolder(parentForCreate, it); showCreateFolder = false }
}

private fun visible(file: ProjectFile, expanded: Set<String>): Boolean {
    if (file.relativePath.count { it == '/' } == 0) return true
    val parts = file.relativePath.split('/')
    var current = ""
    for (part in parts.dropLast(1)) {
        current = if (current.isBlank()) part else "$current/$part"
        if (!expanded.contains(current)) return false
    }
    return true
}

@Composable
private fun FileTreeRow(file: ProjectFile, depth: Int, isExpanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMenu).padding(start = (12 + depth * 18).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (file.isDirectory) Icon(if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, "Toggle folder", Modifier.size(20.dp)) else Spacer(Modifier.width(20.dp))
        Icon(if (file.isDirectory) Icons.Default.Folder else iconForExtension(file.extension), null, tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(file.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = if (file.isDirectory) FontFamily.Default else FontFamily.Monospace, fontSize = 14.sp)
        if (!file.isDirectory && file.modified > 0) Icon(Icons.Default.MoreVert, "File actions", Modifier.clickable(onClick = onMenu))
    }
}

private fun iconForExtension(ext: String): ImageVector = when (ext.lowercase()) {
    "kt", "kts", "java" -> Icons.Default.Code
    "html", "htm", "xml" -> Icons.Default.Web
    "css", "scss" -> Icons.Default.Palette
    "js", "ts", "tsx", "jsx" -> Icons.Default.Javascript
    "json", "yaml", "yml", "toml" -> Icons.Default.DataObject
    "md", "markdown", "txt" -> Icons.Default.Description
    "png", "jpg", "jpeg", "gif", "webp", "svg" -> Icons.Default.Image
    "pdf" -> Icons.Default.PictureAsPdf
    else -> Icons.Default.InsertDriveFile
}

@Composable
private fun FileActionsDialog(file: ProjectFile, onDismiss: () -> Unit, onOpen: () -> Unit, onCreateFile: () -> Unit, onCreateFolder: () -> Unit, onPaste: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, onMove: () -> Unit, onExport: () -> Unit, onShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File actions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionRow("Open", Icons.Default.OpenInNew, onOpen)
                if (file.isDirectory) {
                    ActionRow("New file here", Icons.Default.NoteAdd, onCreateFile)
                    ActionRow("New folder here", Icons.Default.CreateNewFolder, onCreateFolder)
                    ActionRow("Paste here", Icons.Default.ContentPaste, onPaste)
                }
                ActionRow("Rename", Icons.Default.Edit, onRename)
                ActionRow("Duplicate", Icons.Default.ContentCopy, onDuplicate)
                ActionRow("Copy", Icons.Default.ContentCopy, onCopy)
                ActionRow("Move", Icons.Default.DriveFileMove, onMove)
                if (!file.isDirectory) {
                    ActionRow("Export", Icons.Default.FileDownload, onExport)
                    ActionRow("Share", Icons.Default.Share, onShare)
                }
                ActionRow("Delete", Icons.Default.Delete, onDelete)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ActionRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Spacer(Modifier.width(10.dp)); Text(text); Spacer(Modifier.weight(1f)) }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename") }, text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Name") }) }, confirmButton = { Button(enabled = value.isNotBlank(), onClick = { onRename(value) }) { Text("Rename") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ConfirmDeleteDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete $name?") }, text = { Text("This permanently removes the selected item from the project directory.") }, confirmButton = { Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun MoveFileDialog(rootName: String, rootUri: Uri, folders: List<ProjectFile>, file: ProjectFile, onDismiss: () -> Unit, onTarget: (Uri) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${file.name}") },
        text = {
            LazyColumn {
                item { Text("Choose destination folder", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); ListItem(headlineContent = { Text(rootName) }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { onTarget(rootUri) }) }
                items(folders.filter { it.uri != file.uri }) { folder ->
                    ListItem(headlineContent = { Text(folder.relativePath) }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { onTarget(folder.uri) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SimpleNameDialog(title: String, defaultValue: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(defaultValue) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = { Button(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun EditorScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    val active = ws.tabs.firstOrNull { it.path == ws.activeTab }
    val font by vm.fontSize.collectAsState()
    val wordWrap by vm.wordWrap.collectAsState()
    if (active == null) {
        EmptyState(Icons.Default.Code, "No file open", "Open a source file from Explorer to start coding.") { vm.setDestination(Destination.EXPLORER) }
        return
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0A0D12))) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ws.tabs.forEach { tab ->
                Surface(color = if (tab.path == active.path) Color(0xFF1D2530) else Color.Transparent, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(end = 5.dp).clickable { vm.selectTab(tab.path) }) {
                    Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconForExtension(tab.path.substringAfterLast('.', "")), null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text(tab.path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontSize = 12.sp); if (tab.isDirty) Text(" •", color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { vm.closeTab(tab.path) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close tab", modifier = Modifier.size(15.dp)) }
                    }
                }
            }
            TextButton(onClick = { vm.closeOtherTabs(active.path) }) { Text("Close others") }
            TextButton(onClick = vm::reopenLastTab) { Text("Reopen") }
            TextButton(onClick = vm::closeAllTabs) { Text("Close all") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = { vm.setDestination(Destination.SEARCH) }, label = { Text("Find") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            Spacer(Modifier.width(6.dp)); AssistChip(onClick = vm::saveActive, label = { Text("Save") }, leadingIcon = { Icon(Icons.Default.Save, null) })
            Spacer(Modifier.width(6.dp)); AssistChip(onClick = { vm.setDestination(Destination.PROBLEMS) }, label = { Text("Problems") }, leadingIcon = { Icon(Icons.Default.BugReport, null) })
            Spacer(Modifier.weight(1f)); Text("${active.language} · ${if (active.isDirty) "Modified" else "Saved"}", style = MaterialTheme.typography.labelSmall, color = if (active.isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        CodeEditor(active.content, active.language, font.toInt(), wordWrap, vm.tabSize.collectAsState().value, vm::updateActiveContent, vm::saveActive, Modifier.weight(1f))
        CodingToolbar { token ->
            val start = active.selectionStart.coerceIn(0, active.content.length)
            val end = active.selectionEnd.coerceIn(start, active.content.length)
            val next = active.content.replaceRange(start, end, token)
            val cursor = start + token.length
            vm.updateActiveContent(next, cursor, cursor)
        }
    }
}

private fun insertToken(content: String, start: Int, end: Int, token: String): String = content.replaceRange(start.coerceIn(0, content.length), end.coerceIn(start, content.length), token)

@Composable
private fun CodingToolbar(onToken: (String) -> Unit) {
    val keys = listOf("{", "}", "(", ")", "[", "]", "<", ">", "/", "\\", "=", ":", ";", "'", "\"", ".", ",", "_", "-", "+", "*", "Tab", "→", "←", "↑", "↓")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp)) { keys.forEach { key -> AssistChip(onClick = { onToken(if (key == "Tab") "    " else key) }, label = { Text(key, fontFamily = FontFamily.Monospace) }, modifier = Modifier.padding(end = 4.dp)) } }
}

@Composable
private fun PreviewScreen(ws: WorkspaceState.Ready, vm: MainViewModel, context: Context) {
    val active = ws.tabs.firstOrNull { it.path == ws.activeTab }?.let { tab -> ws.files.firstOrNull { it.relativePath == tab.path } }
        ?: ws.files.firstOrNull { !it.isDirectory && it.extension.lowercase() in setOf("html", "htm", "md", "markdown", "json", "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt") }
    if (active == null) { EmptyState(Icons.Default.VisibilityOff, "No previewable file", "Open an HTML, Markdown, JSON, image, text, or PDF file from Explorer."); return }
    val extension = active.extension.lowercase()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Preview", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            AssistChip(onClick = { vm.setDestination(Destination.EDITOR) }, label = { Text("Edit") })
        }
        when (extension) {
            "html", "htm" -> HtmlPreview(active, vm)
            "md", "markdown" -> MarkdownPreview(active, vm)
            "json" -> TextPreview(active, vm, prettyJson = true)
            "png", "jpg", "jpeg", "gif", "webp" -> ImagePreview(active)
            "pdf" -> PdfPreview(active, context)
            else -> TextPreview(active, vm, prettyJson = false)
        }
    }
}

@Composable
private fun HtmlPreview(file: ProjectFile, vm: MainViewModel) {
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.uri, file.modified) { runCatching { vm.buildHtmlPreview(file) }.onSuccess { html = it }.onFailure { error = it.message } }
    if (error != null) { EmptyState(Icons.Default.ErrorOutline, "Preview failed", error ?: "Unable to render HTML") } else if (html == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } } else {
        AndroidView(factory = { ctx -> WebView(ctx).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(AndroidColor.TRANSPARENT)
        } }, update = { web -> web.loadDataWithBaseURL("https://coder.local/", html!!, "text/html", "utf-8", null) }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun MarkdownPreview(file: ProjectFile, vm: MainViewModel) {
    var content by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.uri, file.modified) { content = runCatching { vm.buildHtmlPreview(file) }.getOrNull() }
    if (content == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    AndroidView(factory = { ctx -> WebView(ctx).apply { webViewClient = WebViewClient(); settings.javaScriptEnabled = false; setBackgroundColor(AndroidColor.TRANSPARENT) } }, update = { it.loadDataWithBaseURL("https://coder.local/", markdownToHtml(content!!), "text/html", "utf-8", null) }, modifier = Modifier.fillMaxSize())
}

private fun markdownToHtml(markdown: String): String {
    val escaped = markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val body = escaped.split('\n').joinToString("\n") { line -> when {
        line.startsWith("### ") -> "<h3>${line.substring(4)}</h3>"
        line.startsWith("## ") -> "<h2>${line.substring(3)}</h2>"
        line.startsWith("# ") -> "<h1>${line.substring(2)}</h1>"
        line.startsWith("- ") -> "<li>${line.substring(2)}</li>"
        line.isBlank() -> "<br>"
        else -> "<p>$line</p>"
    } }
    return "<html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:sans-serif;padding:22px;line-height:1.6}</style><body>$body</body></html>"
}

@Composable
private fun TextPreview(file: ProjectFile, vm: MainViewModel, prettyJson: Boolean) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.uri, file.modified) {
        text = runCatching { vm.readPreviewText(file) }.getOrNull()?.let { if (prettyJson) prettyJson(it) else it }
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) { item { Text(text ?: "Loading…", fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp) } }
    }
}

private fun prettyJson(value: String): String = runCatching {
    when {
        value.trimStart().startsWith("[") -> JSONArray(value).toString(2)
        else -> JSONObject(value).toString(2)
    }
}.getOrDefault(value)

@Composable
private fun ImagePreview(file: ProjectFile) {
    AndroidView(factory = { ctx -> ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(AndroidColor.TRANSPARENT) } }, update = { it.setImageURI(file.uri) }, modifier = Modifier.fillMaxSize().padding(12.dp))
}

@Composable
private fun PdfPreview(file: ProjectFile, context: Context) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp)); Text(file.name, style = MaterialTheme.typography.titleLarge)
        Text("PDF viewing is delegated to an installed PDF-capable app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(file.uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = ClipData.newRawUri(file.name, file.uri) }) }) { Text("Open PDF") }
    }
}

@Composable
private fun TerminalScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("Coder Mobile terminal\nProject root: ${ws.project.displayName}\n\nAndroid's app sandbox does not expose a general-purpose shell/runtime. Use Run/Preview for supported project types.\n") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Terminal", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onClick = { output = "" }) { Text("Clear") } }
        Spacer(Modifier.height(8.dp)); Surface(Modifier.weight(1f).fillMaxWidth(), color = Color(0xFF090C10), shape = MaterialTheme.shapes.large) { SelectionContainer { Text(output, Modifier.padding(14.dp), color = Color(0xFFE4E8EF), fontFamily = FontFamily.Monospace, fontSize = 13.sp) } }
        Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Enter command for history") })
            IconButton(onClick = { val command = input.trim(); if (command.isNotBlank()) { output += "\n$ $command\nExecution is unavailable in this Android build.\n"; input = "" } }) { Icon(Icons.Default.Send, "Submit") }
        }
    }
}

@Composable
private fun SearchScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var fileFilter by rememberSaveable { mutableStateOf("") }
    val results by vm.searchResults.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search project") }); Spacer(Modifier.width(8.dp)); Button(enabled = query.isNotBlank(), onClick = { vm.searchProject(query, caseSensitive, wholeWord, regex, fileFilter) }) { Text("Search") } }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            FilterChip(selected = caseSensitive, onClick = { caseSensitive = !caseSensitive }, label = { Text("Case") }); Spacer(Modifier.width(6.dp)); FilterChip(selected = wholeWord, onClick = { wholeWord = !wholeWord }, label = { Text("Whole word") }); Spacer(Modifier.width(6.dp)); FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text("Regex") }); Spacer(Modifier.width(6.dp)); OutlinedTextField(fileFilter, { fileFilter = it }, modifier = Modifier.width(130.dp), singleLine = true, label = { Text("Extension") })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(replacement, { replacement = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Replacement") }); Spacer(Modifier.width(8.dp)); OutlinedButton(enabled = query.isNotBlank(), onClick = { vm.replaceInProject(query, replacement, replaceAll = true, caseSensitive = caseSensitive, regex = regex) }) { Text("Replace all") } }
        if (query.isBlank()) EmptyState(Icons.Default.Search, "Project search", "Search file names and contents, then jump directly to a matching file.")
        else if (results.isEmpty()) EmptyState(Icons.Default.SearchOff, "No matches", "No matching lines were found.")
        else LazyColumn {
            items(results) { result -> ListItem(headlineContent = { Text(result.file.name) }, supportingContent = { Text("${result.file.relativePath}:${result.line}:${result.column}\n${result.snippet}", fontFamily = FontFamily.Monospace) }, leadingContent = { Icon(iconForExtension(result.file.extension), null) }, modifier = Modifier.clickable { vm.openFile(result.file) }) }
        }
    }
}

@Composable
private fun ProblemsScreen(vm: MainViewModel) {
    val problems by vm.problems.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text("Problems", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onClick = vm::clearProblems) { Text("Clear") } }
        if (problems.isEmpty()) EmptyState(Icons.Default.CheckCircle, "No problems", "Basic diagnostics appear here as you open and edit supported files.")
        else LazyColumn {
            items(problems) { p -> ListItem(headlineContent = { Text(p.message) }, supportingContent = { Text("${p.filePath}:${p.line}:${p.column}") }, leadingContent = { Icon(if (p.severity == Severity.ERROR) Icons.Default.Error else if (p.severity == Severity.WARNING) Icons.Default.Warning else Icons.Default.Info, null, tint = if (p.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }) }
        }
    }
}

@Composable
private fun AiScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    val providers by vm.providers.collectAsState()
    val defaultProviderId by vm.defaultProviderId.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var scope by rememberSaveable { mutableStateOf(AiContextScope.CURRENT_FILE) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var response by rememberSaveable { mutableStateOf("Ask about the active file, project structure, errors, or a feature to build.") }
    var busy by remember { mutableStateOf(false) }
    var newFilePath by rememberSaveable { mutableStateOf("src/NewFile.kt") }
    LaunchedEffect(providers, defaultProviderId) { selectedId = providers.firstOrNull { it.id == defaultProviderId }?.id ?: providers.firstOrNull()?.id }
    val selected = providers.firstOrNull { it.id == selectedId }
    val coroutineScope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("AI Assistant", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); AssistChip(onClick = { vm.setDestination(Destination.SETTINGS) }, label = { Text("Providers") }, leadingIcon = { Icon(Icons.Default.Settings, null) }) }
        if (providers.isEmpty()) {
            EmptyState(Icons.Default.AutoAwesome, "Configure an AI provider", "Add an OpenAI or custom OpenAI-compatible provider in Settings → AI Providers.", "Open Settings") { vm.setDestination(Destination.SETTINGS) }
            return
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) { providers.forEach { provider -> FilterChip(selected = selectedId == provider.id, onClick = { selectedId = provider.id }, label = { Text(provider.name) }, modifier = Modifier.padding(end = 6.dp)) } }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) { AiContextScope.values().forEach { value -> FilterChip(selected = scope == value, onClick = { scope = value }, label = { Text(value.name.lowercase().replace('_', ' ')) }, modifier = Modifier.padding(end = 6.dp)) } }
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) { SelectionContainer { LazyColumn(contentPadding = PaddingValues(14.dp)) { item { Text(response, fontFamily = FontFamily.Monospace, fontSize = 13.sp) } } } }
        Spacer(Modifier.height(8.dp)); OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Ask the coding agent") })
        Spacer(Modifier.height(6.dp)); Row(Modifier.horizontalScroll(rememberScrollState())) {
            AssistChip(onClick = { vm.setDestination(Destination.PROBLEMS) }, label = { Text("Explain errors") })
            Spacer(Modifier.width(6.dp)); AssistChip(onClick = { prompt = "Review this project and suggest the highest-value improvements." }, label = { Text("Review project") })
        }
        Spacer(Modifier.height(8.dp)); Button(enabled = selected != null && prompt.isNotBlank() && !busy, onClick = {
            val provider = selected ?: return@Button
            busy = true; response = "Generating…"
            coroutineScope.launch { response = vm.askAi(provider, prompt, scope).getOrElse { "AI request failed: ${it.message}" }; busy = false }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Working…" else "Send") }
        Spacer(Modifier.height(8.dp)); OutlinedButton(enabled = selected != null && prompt.isNotBlank() && !busy && ws.activeTab != null, onClick = {
            val provider = selected ?: return@OutlinedButton
            busy = true
            coroutineScope.launch { response = vm.proposeCurrentFileChange(provider, prompt).getOrElse { "Change proposal failed: ${it.message}" }; busy = false }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Difference, null); Spacer(Modifier.width(6.dp)); Text("Propose change to current file") }
        Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(newFilePath, { newFilePath = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("New file path") }); Spacer(Modifier.width(8.dp)); OutlinedButton(enabled = selected != null && prompt.isNotBlank() && newFilePath.isNotBlank() && !busy, onClick = { val provider = selected ?: return@OutlinedButton; busy = true; coroutineScope.launch { response = vm.proposeNewFile(provider, newFilePath, prompt).getOrElse { "New file proposal failed: ${it.message}" }; busy = false } }) { Text("Create with AI") } }
    }
}

@Composable
private fun BuildScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Run & Build", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("${ws.project.displayName} · ${ws.project.type.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        val detected = when (ws.project.type) {
            ProjectType.WEB -> "Static web project: Preview can execute HTML/CSS/JavaScript inside a WebView."
            ProjectType.ANDROID -> "Gradle/Android project detected. A full Gradle toolchain is not bundled into the app."
            ProjectType.NODE, ProjectType.TYPESCRIPT -> "Node-based project detected. package.json/tsconfig.json can be inspected, but a Node runtime is not bundled."
            ProjectType.PYTHON -> "Python project detected. A Python runtime is not bundled."
            ProjectType.RUST -> "Rust project detected. A Rust toolchain is not bundled."
            ProjectType.GENERIC -> "No recognized build runtime was detected."
        }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) { Text(detected, Modifier.padding(16.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = vm::runProject, modifier = Modifier.weight(1f)) { Text("Run") }; OutlinedButton(onClick = vm::buildProject, modifier = Modifier.weight(1f)) { Text("Build") } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) { OutlinedButton(onClick = { vm.refresh(); vm.setDestination(Destination.BUILD) }, modifier = Modifier.weight(1f)) { Text("Clean/Refresh") }; OutlinedButton(onClick = { vm.setDestination(Destination.PROBLEMS) }, modifier = Modifier.weight(1f)) { Text("Test / Problems") } }
        Spacer(Modifier.height(16.dp))
        Text("The app never pretends an unavailable compiler or runtime is installed. For supported web projects, Run opens the integrated Preview.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GitScreen(ws: WorkspaceState.Ready, vm: MainViewModel) {
    val info = remember(ws.files) { vm.gitInfo() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Git", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp)); Text(ws.project.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Icon(if (info.first) Icons.Default.AccountTree else Icons.Default.FolderOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp)); Text(if (info.first) "Repository detected" else "No Git repository", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp)); Text(info.second, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        if (info.first) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { vm.setDestination(Destination.SEARCH) }) { Text("Inspect files") }; OutlinedButton(onClick = { vm.buildProject() }) { Text("Build") } }
            Spacer(Modifier.height(12.dp)); Text("Commit, branch, pull, push, clone, stage, and diff require a Git engine. Repository detection is kept separate from project editing so the app remains honest about device capabilities.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun shareProjectFile(context: Context, file: ProjectFile) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = when (file.extension.lowercase()) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "pdf" -> "application/pdf"; "html", "htm" -> "text/html"; "json" -> "application/json"; else -> "text/plain"
        }
        putExtra(Intent.EXTRA_STREAM, file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(file.name, file.uri)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share ${file.name}")) }
}

@Composable
private fun SettingsScreen(vm: MainViewModel, context: Context) {
    val theme by vm.theme.collectAsState(); val autosave by vm.autosave.collectAsState(); val font by vm.fontSize.collectAsState(); val tabSize by vm.tabSize.collectAsState(); val wrap by vm.wordWrap.collectAsState(); val providers by vm.providers.collectAsState(); val defaultProvider by vm.defaultProviderId.collectAsState()
    var showProviders by rememberSaveable { mutableStateOf(false) }
    if (showProviders) { AiProviderSettings(vm, providers, defaultProvider, onDismiss = { showProviders = false }); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { SectionHeader("General", Icons.Default.Tune) }
        item { SettingChoiceRow("Appearance", theme, listOf("system", "dark", "light")) { vm.setTheme(it) } }
        item { ListItem(headlineContent = { Text("Autosave") }, supportingContent = { Text("Save edits shortly after changes") }, trailingContent = { Switch(autosave, vm::setAutosave) }) }
        item { SectionHeader("Editor", Icons.Default.Code) }
        item { SettingChoiceRow("Font size", "${font}sp", listOf("12sp", "14sp", "16sp", "18sp", "20sp", "22sp")) { vm.setFontSize(it.removeSuffix("sp").toLong()) } }
        item { SettingChoiceRow("Tab size", "$tabSize spaces", listOf("2 spaces", "4 spaces", "6 spaces", "8 spaces")) { vm.setTabSize(it.substringBefore(' ').toInt()) } }
        item { ListItem(headlineContent = { Text("Word wrap") }, supportingContent = { Text("Wrap long lines in the editor") }, trailingContent = { Switch(wrap, vm::setWordWrap) }) }
        item { SectionHeader("Workspace", Icons.Default.Workspaces) }
        item { SettingsRow("Search", "Project-wide find and replace", Icons.Default.Search) { vm.setDestination(Destination.SEARCH) } }
        item { SettingsRow("Problems", "Diagnostics and AI explain/fix entry point", Icons.Default.BugReport) { vm.setDestination(Destination.PROBLEMS) } }
        item { SettingsRow("Run & Build", "Detected project configuration and supported actions", Icons.Default.PlayArrow) { vm.setDestination(Destination.BUILD) } }
        item { SettingsRow("Terminal", "Session/history surface; runtime execution depends on Android capabilities", Icons.Default.Terminal) { vm.setDestination(Destination.TERMINAL) } }
        item { SettingsRow("Git", "Repository detection and Git workflow entry point", Icons.Default.AccountTree) { vm.setDestination(Destination.GIT) } }
        item { SectionHeader("AI", Icons.Default.AutoAwesome) }
        item { SettingsRow("AI Providers", "${providers.size} configured · default ${providers.firstOrNull { it.id == defaultProvider }?.name ?: "none"}", Icons.Default.Key) { showProviders = true } }
        item { SectionHeader("Security", Icons.Default.Security) }
        item { ListItem(headlineContent = { Text("Credential storage") }, supportingContent = { Text("API keys are encrypted with an Android Keystore-backed key and are never intentionally shown in full.") }) }
        item { ListItem(headlineContent = { Text("App permissions") }, supportingContent = { Text("Only Internet access is declared; project access is granted per directory through Android's picker.") }, trailingContent = { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Manage") } }) }
        item { SectionHeader("About", Icons.Default.Info) }
        item { ListItem(headlineContent = { Text("Coder Mobile") }, supportingContent = { Text("1.0.0 · Kotlin · Jetpack Compose · com.aprax.coderm") }) }
    }
}

@Composable
private fun SettingChoiceRow(title: String, current: String, choices: List<String>, onChoice: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(current) }, trailingContent = {
        Box { TextButton(onClick = { expanded = true }) { Text("Change") }; DropdownMenu(expanded, { expanded = false }) { choices.forEach { choice -> DropdownMenuItem({ Text(choice) }, onClick = { expanded = false; onChoice(choice) }) } } }
    })
}

@Composable
private fun AiProviderSettings(vm: MainViewModel, providers: List<AiProviderConfig>, defaultProvider: String, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<AiProviderConfig?>(null) }
    var showNew by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Back") }; Text("AI Providers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); Button(onClick = { showNew = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add") } }
        Text("OpenAI-compatible endpoint support. Use HTTPS endpoints and keep credentials private.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        if (providers.isEmpty()) EmptyState(Icons.Default.Key, "No providers", "Add a provider with its endpoint, model, and API key.")
        else LazyColumn {
            items(providers) { provider ->
                ListItem(headlineContent = { Text(provider.name) }, supportingContent = { Text("${provider.baseUrl}\nModel: ${provider.model}${if (provider.id == defaultProvider) " · Default" else ""}") }, leadingContent = { Icon(Icons.Default.AutoAwesome, null) }, trailingContent = { Row { IconButton(onClick = { editing = provider }) { Icon(Icons.Default.Edit, "Edit") }; IconButton(onClick = { vm.removeProvider(provider) }) { Icon(Icons.Default.Delete, "Delete") } } }, modifier = Modifier.clickable { editing = provider })
            }
        }
    }
    if (showNew) ProviderDialog(null, vm, providers, onDismiss = { showNew = false })
    editing?.let { ProviderDialog(it, vm, providers, onDismiss = { editing = null }) }
}

@Composable
private fun ProviderDialog(existing: AiProviderConfig?, vm: MainViewModel, providers: List<AiProviderConfig>, onDismiss: () -> Unit) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "OpenAI") }
    var base by rememberSaveable(existing?.id) { mutableStateOf(existing?.baseUrl ?: "https://api.openai.com/v1") }
    var model by rememberSaveable(existing?.id) { mutableStateOf(existing?.model ?: "gpt-4.1-mini") }
    var key by rememberSaveable(existing?.id) { mutableStateOf("") }
    var organization by rememberSaveable(existing?.id) { mutableStateOf(existing?.organization ?: "") }
    var apiVersion by rememberSaveable(existing?.id) { mutableStateOf(existing?.apiVersion ?: "") }
    var timeout by rememberSaveable(existing?.id) { mutableStateOf(existing?.timeoutSeconds?.toString() ?: "60") }
    var maxTokens by rememberSaveable(existing?.id) { mutableStateOf(existing?.maxTokens?.toString() ?: "2048") }
    var temperature by rememberSaveable(existing?.id) { mutableStateOf(existing?.temperature?.toString() ?: "0.2") }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val id = existing?.id ?: "provider-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "custom" }}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add AI provider" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Provider Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text(if (existing == null) "API Key" else "Replace API Key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(organization, { organization = it }, label = { Text("Organization (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiVersion, { apiVersion = it }, label = { Text("API version/header (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(timeout, { timeout = it }, label = { Text("Timeout s") }, singleLine = true, modifier = Modifier.weight(1f)); OutlinedTextField(maxTokens, { maxTokens = it }, label = { Text("Max tokens") }, singleLine = true, modifier = Modifier.weight(1f)) }
                OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("API keys are stored separately using Android Keystore-backed encryption.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let { Text(it, color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !testing && name.isNotBlank() && base.isNotBlank() && model.isNotBlank(), onClick = {
                    testing = true; status = "Testing…"
                    scope.launch {
                        val cfg = AiProviderConfig(id, name.trim(), base.trim(), model.trim(), key, organization.trim(), apiVersion.trim(), timeout.toLongOrNull() ?: 60L, maxTokens.toIntOrNull() ?: 2048, temperature.toDoubleOrNull() ?: 0.2)
                        status = vm.testProvider(cfg).getOrElse { it.message ?: "Connection failed" }; testing = false
                    }
                }) { Text(if (testing) "Testing" else "Test") }
                Button(enabled = name.isNotBlank() && base.isNotBlank() && model.isNotBlank(), onClick = {
                    val cfg = AiProviderConfig(id, name.trim(), base.trim(), model.trim(), key, organization.trim(), apiVersion.trim(), timeout.toLongOrNull() ?: 60L, maxTokens.toIntOrNull() ?: 2048, temperature.toDoubleOrNull() ?: 0.2)
                    vm.saveProvider(cfg); if (providers.isEmpty()) vm.setDefaultProvider(id); onDismiss()
                }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
