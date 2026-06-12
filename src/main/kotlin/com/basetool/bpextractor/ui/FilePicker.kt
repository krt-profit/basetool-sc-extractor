package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Model + pure logic (no Compose) — unit-tested in FilePickerTest.
// ---------------------------------------------------------------------------

/** What the picker selects: an existing directory, or a (possibly new) file to save. */
enum class PickerMode { FOLDER, SAVE_FILE }

/** Sortable list columns (`REDESIGN_IMPLEMENTATION.md` §10): name, size, last-modified. */
enum class PickerSortKey { NAME, SIZE, MODIFIED }

/** One row in the browser, with the metadata the sortable columns render. */
data class PickerEntry(
    val file: File,
    val isDirectory: Boolean,
    /** File size in bytes (0 for directories — the size column shows the folder tag instead). */
    val size: Long = 0,
    /** Last-modified epoch millis (0 if unknown). */
    val modified: Long = 0,
)

/** Result of listing a directory. [Denied] = it couldn't be read (no access / gone). */
sealed interface Listing {
    data class Ok(val entries: List<PickerEntry>) : Listing
    data object Denied : Listing
}

/** A request to open the picker (held in app state); [onConfirm] gets the chosen absolute path. */
data class PickerRequest(
    val mode: PickerMode,
    val title: String,
    val confirmLabel: String,
    val initialPath: String,
    val extension: String = "json",
    val onConfirm: (String) -> Unit,
)

/**
 * Read-only listing of what to show inside [dir] for [mode]:
 *  - [dir] == null -> the drive roots ("Computer").
 *  - FOLDER        -> sub-directories plus ALL files (the UI renders files dimmed/unselectable).
 *  - SAVE_FILE     -> sub-directories plus files ending in `.[extension]`.
 *
 * Hidden entries are skipped; results are sorted directories-first, then case-insensitive by
 * name (re-sortable via [sortEntries]). Returns [Listing.Denied] when the directory can't be
 * enumerated — it never throws and never writes, so a bad path is harmless.
 */
fun listChildren(dir: File?, mode: PickerMode, extension: String): Listing {
    if (dir == null) {
        return Listing.Ok(File.listRoots().orEmpty().map { PickerEntry(it, isDirectory = true) })
    }
    val children = dir.listFiles() ?: return Listing.Denied
    val ext = extension.removePrefix(".").lowercase()
    val entries = children.asSequence()
        .filterNot { it.isHidden }
        .filter { f -> f.isDirectory || mode == PickerMode.FOLDER || matchesExtension(f.name, ext) }
        .map { PickerEntry(it, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
        .toList()
    return Listing.Ok(sortEntries(entries, PickerSortKey.NAME, ascending = true))
}

private fun matchesExtension(name: String, extLower: String): Boolean =
    extLower.isEmpty() || name.lowercase().endsWith(".$extLower")

/** Sort [entries] directories-first, then by [key] in the given direction (§10 sortable list). */
fun sortEntries(entries: List<PickerEntry>, key: PickerSortKey, ascending: Boolean): List<PickerEntry> {
    val comparator: Comparator<PickerEntry> = when (key) {
        PickerSortKey.NAME -> compareBy { it.file.name.lowercase() }
        PickerSortKey.SIZE -> compareBy { it.size }
        PickerSortKey.MODIFIED -> compareBy { it.modified }
    }
    val directed = if (ascending) comparator else comparator.reversed()
    return entries.sortedWith(compareByDescending<PickerEntry> { it.isDirectory }.then(directed))
}

/** Append `.[extension]` to [name] unless it already ends with it (case-insensitive). */
fun ensureExtension(name: String, extension: String): String {
    val ext = extension.removePrefix(".")
    if (ext.isEmpty()) return name
    return if (name.lowercase().endsWith(".${ext.lowercase()}")) name else "$name.$ext"
}

/**
 * Whether [name] is a legal Windows file name for the SAVE footer validation: non-blank and free
 * of the reserved characters `\ / : * ? " < > |`.
 */
fun isValidFileName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.none { it in "\\/:*?\"<>|" }
}

/** The breadcrumb chain of [dir], root-first (e.g. `C:\ › Users › greluc`); empty for null. */
fun parentChain(dir: File?): List<File> {
    val chain = ArrayDeque<File>()
    var cursor = dir
    while (cursor != null) {
        chain.addFirst(cursor)
        cursor = cursor.parentFile
    }
    return chain.toList()
}

/** Directory to open in, derived from a pre-filled [path] (null = show drive roots). */
fun initialDirectory(path: String, mode: PickerMode): File? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val f = File(trimmed)
    return when (mode) {
        PickerMode.FOLDER -> when {
            f.isDirectory -> f
            f.parentFile?.isDirectory == true -> f.parentFile
            else -> null
        }
        PickerMode.SAVE_FILE -> f.parentFile?.takeIf { it.isDirectory } ?: f.takeIf { it.isDirectory }
    }
}

/** Filename to pre-fill in SAVE mode: the last segment of [path], or [default] if none. */
fun initialFileName(path: String, default: String): String =
    File(path.trim()).name.ifBlank { default }

/** Result of resolving a typed/pasted path: a target [dir] (null = roots) + optional [fileName]. */
data class TypedPath(val dir: File?, val fileName: String?)

/**
 * Resolve a typed/pasted [text] into a target directory (and, in SAVE_FILE mode, a filename).
 * Input is normalized first (see [normalizePathInput]); relative input resolves against [base]
 * when one is given (the path bar passes the current directory, the filter field doesn't).
 * Returns a [TypedPath] with `dir == null` for empty input (drive roots), or null if it can't
 * resolve to an existing directory — the caller surfaces that as an input error. A path that
 * names a *file* resolves to its parent directory. Read-only — never creates or writes anything.
 */
fun resolveTypedPath(text: String, mode: PickerMode, base: File? = null): TypedPath? {
    val raw = normalizePathInput(text)
    if (raw.isEmpty()) return TypedPath(null, null)
    val typed = File(raw)
    val f = when {
        typed.isAbsolute -> typed
        base != null && isPlainRelative(raw) -> File(base, raw)
        else -> typed
    }
    return when {
        f.isDirectory -> TypedPath(tidyDir(f), null)
        f.parentFile?.isDirectory == true ->
            TypedPath(tidyDir(f.parentFile), if (mode == PickerMode.SAVE_FILE && f.name.isNotBlank()) f.name else null)
        else -> null
    }
}

/**
 * Normalize raw path-bar input into something [File] understands. Handles, in order: the first
 * non-blank line of a multi-line paste; surrounding double quotes (Explorer's "Copy as path") or
 * single quotes (PowerShell); `file:` URIs (browser / Explorer address bar); the `~` home
 * shorthand; `%VAR%` environment variables (unknown ones stay literal); and a bare drive letter
 * (`C:` -> `C:\`, which [File] would otherwise treat as "the process working directory on C:").
 * [env] is injectable for tests.
 */
internal fun normalizePathInput(text: String, env: (String) -> String? = System::getenv): String {
    var s = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
    s = s.removeSurrounding("\"").removeSurrounding("'").trim()
    if (s.startsWith("file:", ignoreCase = true)) s = fileUriToPath(s)
    if (s == "~") {
        s = System.getProperty("user.home")
    } else if (s.startsWith("~\\") || s.startsWith("~/")) {
        s = System.getProperty("user.home") + s.substring(1)
    }
    s = ENV_VAR.replace(s) { m -> env(m.groupValues[1]) ?: m.value }
    if (DRIVE_ONLY.matches(s)) s += "\\"
    return s.trim()
}

private val ENV_VAR = Regex("%([^%]+)%")
private val DRIVE_ONLY = Regex("[A-Za-z]:")

/** `file:` URI -> filesystem path; tolerates UNC authorities and unencoded characters. */
private fun fileUriToPath(uri: String): String {
    runCatching { return File(URI(uri)).path }
    val body = uri.substring("file:".length)
    // URLDecoder would turn a literal '+' into a space — shield it before decoding.
    val decoded = runCatching { URLDecoder.decode(body.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(body)
    return when {
        // file://server/share -> UNC \\server\share (File(URI) rejects authorities)
        decoded.startsWith("//") && !decoded.startsWith("///") -> "\\\\" + decoded.removePrefix("//")
        else -> decoded.trimStart('/')
    }
}

/** True when [path] has no drive prefix and no leading separator — i.e. relative to the current dir. */
private fun isPlainRelative(path: String): Boolean =
    !path.startsWith("\\") && !path.startsWith("/") && !(path.length >= 2 && path[1] == ':')

/** Absolute form of [f] for clean breadcrumbs; canonicalized only when `.`/`..` segments remain. */
private fun tidyDir(f: File?): File? {
    val abs = f?.absoluteFile ?: return null
    val hasDots = abs.path.split('\\', '/').any { it == "." || it == ".." }
    return if (hasDots) runCatching { abs.canonicalFile }.getOrDefault(abs) else abs
}

/** Roots report an empty name (`File("C:\\").name == ""`) — fall back to the path so they show. */
private fun displayName(file: File): String = file.name.ifBlank { file.path.removeSuffix("\\") }

/** Human-readable size for the list column (B / KB / MB / GB). */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

private val MODIFIED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")

/** Last-modified column text; blank when the filesystem reported nothing. */
private fun formatModified(epochMillis: Long): String =
    if (epochMillis <= 0) "" else MODIFIED_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** The quick-access sidebar targets that exist on this machine (Home/Documents/Desktop/Downloads). */
private fun quickAccess(strings: Strings): List<Pair<String, File>> {
    val home = File(System.getProperty("user.home"))
    return listOf(
        strings.pickerHome to home,
        strings.pickerDocuments to File(home, "Documents"),
        strings.pickerDesktop to File(home, "Desktop"),
        strings.pickerDownloads to File(home, "Downloads"),
    ).filter { it.second.isDirectory }
}

// ---------------------------------------------------------------------------
// Composable UI
// ---------------------------------------------------------------------------

/**
 * KRT-styled, in-app file/folder browser shown as a modal overlay (`REDESIGN_IMPLEMENTATION.md`
 * §10) — never a native OS dialog. Layout: header · toolbar (parent-folder button, clickable
 * breadcrumb that doubles as an editable path bar — click its free area or Ctrl+L, then type or
 * paste a path and press Enter; an unresolvable path shows an inline error — a ✕ button that
 * empties the path bar for a one-click paste, filter field that also accepts a pasted path,
 * new-folder in SAVE mode) · quick-access/drives sidebar + sortable
 * folders-first list (type icons, size, date) · footer with the filename field (SAVE: overwrite
 * warning + name validation) or the selected path (FOLDER), and the one orange CTA. Keyboard:
 * Esc closes, Enter confirms, Backspace goes up, Ctrl+L edits the path. FOLDER mode shows files
 * dimmed and unselectable. Browsing is read-only; the only write is the explicit new-folder
 * action.
 */
@Composable
fun FilePickerDialog(
    mode: PickerMode,
    title: String,
    confirmLabel: String,
    initialPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extension: String = "json",
) {
    val strings = LocalStrings.current
    var currentDir by remember { mutableStateOf(initialDirectory(initialPath, mode)) }
    var fileName by remember {
        mutableStateOf(if (mode == PickerMode.SAVE_FILE) initialFileName(initialPath, "blueprints.$extension") else "")
    }
    var listing by remember { mutableStateOf<Listing?>(null) } // null = (re)loading
    var selected by remember { mutableStateOf<File?>(null) }
    var sortKey by remember { mutableStateOf(PickerSortKey.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var newFolderOpen by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var pathEditing by remember { mutableStateOf(false) }
    // Hoisted so the clear button next to the field can empty it (paste-a-path flow).
    var pathFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var pathError by remember { mutableStateOf(false) }
    var pathResolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // List off the UI thread so a slow/network directory never janks composition.
    LaunchedEffect(currentDir, reloadTick) {
        listing = null
        listing = withContext(Dispatchers.IO) { listChildren(currentDir, mode, extension) }
    }

    fun navigateTo(dir: File?) {
        currentDir = dir
        selected = null
        query = ""
        createError = false
        newFolderOpen = false
        pathError = false
    }

    // Open the path bar pre-filled with the current directory, fully selected so a paste replaces it.
    fun startPathEdit(initial: String = currentDir?.absolutePath ?: "") {
        pathFieldValue = TextFieldValue(initial, selection = TextRange(0, initial.length))
        pathError = false
        pathEditing = true
    }

    // Resolve off the UI thread: File.isDirectory on an unreachable UNC path can block for seconds.
    fun commitTypedPath(text: String) {
        if (pathResolving) return
        pathResolving = true
        scope.launch {
            val resolved = withContext(Dispatchers.IO) { resolveTypedPath(text, mode, currentDir) }
            pathResolving = false
            if (resolved == null) {
                pathError = true
            } else {
                navigateTo(resolved.dir)
                resolved.fileName?.let { fileName = it }
                pathEditing = false
            }
        }
    }

    val entries = (listing as? Listing.Ok)?.entries.orEmpty()
    val view = remember(entries, query, sortKey, sortAscending) {
        val filtered = if (query.isBlank()) {
            entries
        } else {
            entries.filter { it.file.name.contains(query.trim(), ignoreCase = true) }
        }
        sortEntries(filtered, sortKey, sortAscending)
    }

    val nameValid = mode != PickerMode.SAVE_FILE || isValidFileName(fileName)
    val finalName = if (mode == PickerMode.SAVE_FILE) ensureExtension(fileName.trim(), extension) else ""
    val fileExists = mode == PickerMode.SAVE_FILE &&
        entries.any { !it.isDirectory && it.file.name.equals(finalName, ignoreCase = true) }
    val selectedDir = selected?.takeIf { it.isDirectory }
    val targetPath = when (mode) {
        PickerMode.FOLDER -> (selectedDir ?: currentDir)?.absolutePath
        PickerMode.SAVE_FILE -> currentDir?.let { File(it, finalName).absolutePath }
    }
    val canConfirm = currentDir != null && targetPath != null && (mode == PickerMode.FOLDER || nameValid)

    fun confirm() {
        val target = targetPath ?: return
        if (canConfirm) onConfirm(target)
    }

    fun goUp() {
        if (currentDir != null) navigateTo(currentDir?.parentFile)
    }

    val rootFocus = remember { FocusRequester() }
    // Also refocuses the dialog after the path-edit field closes, so Esc/Backspace work again.
    LaunchedEffect(pathEditing) { if (!pathEditing) rootFocus.requestFocus() }
    val swallow = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Krt.Black.copy(alpha = 0.82f))
            .focusRequester(rootFocus)
            .focusable()
            // Bubbling (child-first) so text fields keep their own Enter/Backspace handling.
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        if (canConfirm && !newFolderOpen) {
                            confirm()
                            true
                        } else {
                            false
                        }
                    }
                    Key.Backspace -> {
                        goUp()
                        true
                    }
                    Key.L -> {
                        // Ctrl+L (Explorer/browser convention): edit the path bar.
                        if (e.isCtrlPressed) {
                            startPathEdit()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss, // click outside the panel = cancel
            )
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 880.dp)
                .heightIn(max = 620.dp)
                .fillMaxSize()
                .drawBehind {
                    val grow = 26.dp.toPx()
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Krt.Orange.copy(alpha = 0.16f), Color.Transparent),
                            center = center,
                            radius = size.maxDimension / 2f + grow,
                        ),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + 2f * grow, size.height + 2f * grow),
                    )
                }
                .background(Krt.Black.copy(alpha = 0.98f))
                .border(1.dp, Krt.Orange)
                .drawWithContent {
                    drawContent()
                    val len = 12.dp.toPx()
                    val w = 2.dp.toPx()
                    val o = w / 2f
                    drawLine(Krt.Orange, Offset(o, o), Offset(len, o), w)
                    drawLine(Krt.Orange, Offset(o, o), Offset(o, len), w)
                    drawLine(Krt.Orange, Offset(size.width - o, size.height - o), Offset(size.width - len, size.height - o), w)
                    drawLine(Krt.Orange, Offset(size.width - o, size.height - o), Offset(size.width - o, size.height - len), w)
                }
                .clickable(interactionSource = swallow, indication = null, onClick = {}), // swallow panel clicks
        ) {
            // --- Header ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4)
                    .drawBehind { drawLine(Krt.Orange, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PickerSquareButton("✕", strings.close, onClick = onDismiss)
            }

            // --- Toolbar: parent + breadcrumb + filter (+ new folder in SAVE mode) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4.copy(alpha = 0.5f))
                    .drawBehind { drawLine(Krt.Gray3, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerSquareButton("↑", strings.pickerParentFolder, enabled = currentDir != null, onClick = ::goUp)
                if (pathEditing) {
                    PathEditField(
                        value = pathFieldValue,
                        onValueChange = {
                            pathFieldValue = it
                            pathError = false
                        },
                        error = pathError,
                        onCommit = ::commitTypedPath,
                        onCancel = {
                            pathEditing = false
                            pathError = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Breadcrumb(
                        currentDir,
                        onNavigate = ::navigateTo,
                        onEdit = { startPathEdit() },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Clears the path bar (opening it first if needed) so a copied path can be pasted straight in.
                PickerSquareButton("✕", strings.pickerClearPath) { startPathEdit(initial = "") }
                FilterField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = strings.pickerFilter,
                    onCommitPath = { text ->
                        resolveTypedPath(text, mode)?.let { resolved ->
                            navigateTo(resolved.dir)
                            resolved.fileName?.let { fileName = it }
                            true
                        } ?: false
                    },
                )
                if (mode == PickerMode.SAVE_FILE) {
                    PickerSquareButton("+", strings.pickerNewFolder, enabled = currentDir != null) {
                        newFolderOpen = true
                        createError = false
                    }
                }
            }

            // Input error from the path bar: the typed/pasted path didn't resolve.
            if (pathError) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Krt.Danger.copy(alpha = 0.08f))
                        .drawBehind { drawLine(Krt.Gray3, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(strings.pickerPathNotFound, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
                }
            }

            // --- Body: sidebar + list ---
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Sidebar: quick access + drives.
                Column(
                    modifier = Modifier
                        .width(188.dp)
                        .fillMaxHeight()
                        .background(Krt.Gray4)
                        .drawBehind { drawLine(Krt.Gray3, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        strings.pickerQuickAccess.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Krt.Gray2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    val quick = remember(strings) { quickAccess(strings) }
                    quick.forEach { (label, dir) ->
                        SideItem(label, active = currentDir?.absolutePath == dir.absolutePath) { navigateTo(dir) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        strings.pickerDrives.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Krt.Gray2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    val drives = remember { File.listRoots().orEmpty().toList() }
                    drives.forEach { root ->
                        SideItem(
                            displayName(root),
                            active = currentDir?.absolutePath == root.absolutePath,
                        ) { navigateTo(root) }
                    }
                }

                // List: sortable header + rows + extension-filter note.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ListHeader(
                        sortKey = sortKey,
                        ascending = sortAscending,
                        onSort = { key ->
                            if (sortKey == key) sortAscending = !sortAscending else {
                                sortKey = key
                                sortAscending = true
                            }
                        },
                    )
                    Box(Modifier.weight(1f).fillMaxWidth().background(Krt.SurfaceInput.copy(alpha = 0.4f))) {
                        when (val l = listing) {
                            null -> CenteredNote(strings.pickerLoading)
                            is Listing.Denied -> CenteredNote(strings.pickerDenied)
                            is Listing.Ok -> Column(Modifier.fillMaxSize()) {
                                if (newFolderOpen) {
                                    NewFolderRow(
                                        onCreate = { name ->
                                            val dir = currentDir
                                            val ok = dir != null && isValidFileName(name) &&
                                                runCatching { File(dir, name.trim()).mkdir() }.getOrDefault(false)
                                            if (ok) {
                                                newFolderOpen = false
                                                createError = false
                                                reloadTick++
                                            } else {
                                                createError = true
                                            }
                                        },
                                        onCancel = {
                                            newFolderOpen = false
                                            createError = false
                                        },
                                        error = createError,
                                    )
                                }
                                if (view.isEmpty() && !newFolderOpen) {
                                    CenteredNote(if (query.isBlank()) strings.pickerEmpty else strings.pickerNoMatch)
                                } else {
                                    LazyColumn(Modifier.fillMaxSize()) {
                                        items(view, key = { it.file.path }) { entry ->
                                            val dimmed = mode == PickerMode.FOLDER && !entry.isDirectory
                                            PickerRow(
                                                entry = entry,
                                                strings = strings,
                                                selected = selected?.path == entry.file.path,
                                                dimmed = dimmed,
                                                onSelect = {
                                                    if (!dimmed) {
                                                        selected = entry.file
                                                        if (mode == PickerMode.SAVE_FILE && !entry.isDirectory) {
                                                            fileName = entry.file.name
                                                        }
                                                    }
                                                },
                                                onActivate = {
                                                    when {
                                                        entry.isDirectory -> navigateTo(entry.file)
                                                        mode == PickerMode.SAVE_FILE -> {
                                                            fileName = entry.file.name
                                                            confirm()
                                                        }
                                                        else -> {}
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (mode == PickerMode.SAVE_FILE) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Krt.SurfaceInput)
                                .drawBehind { drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            Text(
                                strings.pickerShowing("*.${extension.removePrefix(".")}"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Krt.Gray2,
                            )
                        }
                    }
                }
            }

            // --- Footer: filename (SAVE) / selected path (FOLDER) + warnings + actions ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Krt.Gray4)
                    .drawBehind { drawLine(Krt.Orange, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx()) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (mode == PickerMode.SAVE_FILE) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            strings.pickerFileName.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Krt.Gray1,
                        )
                        val borderColor = when {
                            !nameValid -> Krt.Danger
                            fileExists -> Krt.Warning
                            else -> Krt.Gray3
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .background(Krt.SurfaceInput)
                                .border(1.dp, borderColor)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = fileName,
                                onValueChange = { fileName = it },
                                singleLine = true,
                                textStyle = KrtDataStyle.copy(color = Krt.White),
                                cursorBrush = SolidColor(Krt.Orange),
                                modifier = Modifier.weight(1f),
                            )
                            Text(".${extension.removePrefix(".")}", style = KrtDataStyle, color = Krt.Gray2)
                        }
                    }
                    if (fileExists && nameValid) {
                        Spacer(Modifier.height(6.dp))
                        Text(strings.pickerOverwrite, style = MaterialTheme.typography.bodySmall, color = Krt.Warning)
                    }
                    if (!nameValid) {
                        Spacer(Modifier.height(6.dp))
                        Text(strings.pickerInvalidName, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            strings.pickerSelected.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Krt.Gray2,
                        )
                        Text(
                            targetPath ?: strings.pickerComputer,
                            style = KrtDataStyle,
                            color = Krt.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (createError) {
                    Spacer(Modifier.height(6.dp))
                    Text(strings.pickerCreateFailed, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        currentDir?.absolutePath ?: strings.pickerComputer,
                        style = MaterialTheme.typography.bodySmall,
                        color = Krt.Gray2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(strings.cancel, onClick = onDismiss)
                    Spacer(Modifier.width(10.dp))
                    CtaButton(confirmLabel, enabled = canConfirm, onClick = ::confirm)
                }
            }
        }
    }
}

/** A 32dp square hairline button (parent-folder, new-folder, close) with orange hover. */
@Composable
private fun PickerSquareButton(
    glyph: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = hovered && enabled
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Krt.SurfaceInput)
            .border(1.dp, if (active) Krt.Orange else Krt.Gray3)
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                active -> Krt.Orange
                enabled -> Krt.Gray1
                else -> Krt.Gray3
            },
        )
        // description is conveyed via the surrounding context; kept as parameter for call-site clarity
    }
}

/**
 * The clickable breadcrumb: Computer › C:\ › Users › … (horizontally scrollable). Clicking its
 * free area (the crumbs consume their own clicks) switches to the editable path bar, Explorer-
 * style — Ctrl+L does the same; the hover border signals the affordance.
 */
@Composable
private fun Breadcrumb(
    currentDir: File?,
    onNavigate: (File?) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val chain = remember(currentDir) { parentChain(currentDir) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .height(32.dp)
            .background(Krt.SurfaceInput)
            .border(1.dp, if (hovered) Krt.Orange else Krt.Gray3)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onEdit)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrumbButton(strings.pickerComputer, active = currentDir == null) { onNavigate(null) }
        chain.forEachIndexed { index, segment ->
            Text("›", style = MaterialTheme.typography.bodySmall, color = Krt.Gray3, modifier = Modifier.padding(horizontal = 2.dp))
            CrumbButton(displayName(segment), active = index == chain.lastIndex) { onNavigate(segment) }
        }
    }
}

/**
 * The breadcrumb's edit mode: a controlled path field ([value] is hoisted so the toolbar's clear
 * button can empty it). Enter resolves and navigates (async — see commitTypedPath), Esc or losing
 * focus reverts to the breadcrumb. While [error] is set the border turns red and the input stays
 * for correction; any edit clears the error at the call site.
 */
@Composable
private fun PathEditField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: Boolean,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val focus = remember { FocusRequester() }
    // Only cancel on focus LOSS, not on the initial unfocused state before requestFocus lands.
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = modifier
            .height(32.dp)
            .background(Krt.SurfaceInput)
            .border(1.dp, if (error) Krt.Danger else Krt.Orange)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.text.isEmpty()) {
                Text(strings.pickerPathPlaceholder, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = KrtDataStyle.copy(color = Krt.White),
                cursorBrush = SolidColor(Krt.Orange),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { state ->
                        if (state.isFocused) hadFocus = true else if (hadFocus) onCancel()
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                onCommit(value.text)
                                true
                            }
                            Key.Escape -> {
                                onCancel()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
    }
}

/** One breadcrumb segment: white when current, grey otherwise; orange on hover. */
@Composable
private fun CrumbButton(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        style = KrtDataStyle,
        color = when {
            hovered -> Krt.Orange
            active -> Krt.White
            else -> Krt.Gray2
        },
        maxLines = 1,
        modifier = Modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** The compact type-to-filter field; Enter with a pasted path navigates there instead. */
@Composable
private fun FilterField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onCommitPath: (String) -> Boolean,
) {
    Row(
        modifier = Modifier
            .width(180.dp)
            .height(32.dp)
            .background(Krt.SurfaceInput)
            .border(1.dp, Krt.Gray3)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Krt.Gray2)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Krt.White),
                cursorBrush = SolidColor(Krt.Orange),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            onCommitPath(value)
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

/** One sidebar item (quick access / drive): 2dp orange left edge + wash when active. */
@Composable
private fun SideItem(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(
                when {
                    active -> Krt.Orange.copy(alpha = 0.1f)
                    hovered -> Krt.Orange.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .drawBehind {
                if (active) drawRect(Krt.Orange, size = Size(2.dp.toPx(), size.height))
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(if (active || hovered) Krt.Orange else Krt.Gray2))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (active || hovered) Krt.Orange else Krt.Gray1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The sortable column header: Name · Größe · Geändert; clicking toggles direction. */
@Composable
private fun ListHeader(sortKey: PickerSortKey, ascending: Boolean, onSort: (PickerSortKey) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Krt.SurfaceInput)
            .drawBehind { drawLine(Krt.Gray3, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val strings = LocalStrings.current
        HeaderCell(strings.pickerColName, PickerSortKey.NAME, sortKey, ascending, Modifier.weight(1f), onSort)
        HeaderCell(strings.pickerColSize, PickerSortKey.SIZE, sortKey, ascending, Modifier.width(86.dp), onSort, alignEnd = true)
        HeaderCell(strings.pickerColModified, PickerSortKey.MODIFIED, sortKey, ascending, Modifier.width(120.dp), onSort, alignEnd = true)
    }
}

/** One sortable header cell with the ▲/▼ direction marker on the active column. */
@Composable
private fun HeaderCell(
    label: String,
    key: PickerSortKey,
    activeKey: PickerSortKey,
    ascending: Boolean,
    modifier: Modifier,
    onSort: (PickerSortKey) -> Unit,
    alignEnd: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = key == activeKey
    val marker = if (!active) "" else if (ascending) " ▲" else " ▼"
    Text(
        (label + marker).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = when {
            hovered -> Krt.Orange
            active -> Krt.Gray1
            else -> Krt.Gray2
        },
        maxLines = 1,
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        modifier = modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = { onSort(key) }),
    )
}

/**
 * One list row: type icon (folder orange / image / file), name, size and modified columns.
 * Single click selects (orange wash + left edge), double click enters a directory or confirms a
 * file (SAVE mode). [dimmed] rows (files in FOLDER mode) are faded and ignore clicks.
 */
@Composable
private fun PickerRow(
    entry: PickerEntry,
    strings: Strings,
    selected: Boolean,
    dimmed: Boolean,
    onSelect: () -> Unit,
    onActivate: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val contentAlpha = if (dimmed) 0.45f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> Krt.Orange.copy(alpha = 0.14f)
                    hovered && !dimmed -> Krt.Gray3
                    else -> Color.Transparent
                },
            )
            .drawBehind {
                if (selected) drawRect(Krt.Orange, size = Size(2.dp.toPx(), size.height))
            }
            .hoverable(interaction, enabled = !dimmed)
            .pointerInput(entry.file.path, dimmed) {
                detectTapGestures(
                    onTap = { if (!dimmed) onSelect() },
                    onDoubleTap = { if (!dimmed) onActivate() },
                )
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EntryIcon(entry, alpha = contentAlpha)
        Text(
            displayName(entry.file),
            style = MaterialTheme.typography.bodySmall,
            color = (if (selected) Krt.White else Krt.Gray1).copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (entry.isDirectory) strings.pickerFolderType else formatSize(entry.size),
            style = KrtDataStyle,
            color = Krt.Gray2.copy(alpha = contentAlpha),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(86.dp),
        )
        Text(
            formatModified(entry.modified),
            style = KrtDataStyle,
            color = Krt.Gray2.copy(alpha = contentAlpha),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(120.dp),
        )
    }
}

/** Image-file extensions that earn the image type icon in the list. */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "webp")

/**
 * The 16dp type icon, hand-drawn in the HUD idiom (no icon-font dependency): folders are an
 * orange folder silhouette, images a framed picture with a horizon line, other files a plain
 * document outline.
 */
@Composable
private fun EntryIcon(entry: PickerEntry, alpha: Float) {
    val isImage = !entry.isDirectory && entry.file.extension.lowercase() in IMAGE_EXTENSIONS
    Box(
        modifier = Modifier
            .size(16.dp)
            .drawBehind {
                val s = 1.3.dp.toPx()
                when {
                    entry.isDirectory -> {
                        val tabW = size.width * 0.42f
                        val tabH = size.height * 0.2f
                        val bodyTop = tabH * 0.8f
                        drawRect(
                            Krt.Orange.copy(alpha = alpha),
                            topLeft = Offset(0f, bodyTop),
                            size = Size(size.width, size.height - bodyTop - 1f),
                        )
                        drawRect(Krt.Orange.copy(alpha = alpha), topLeft = Offset(0f, 0f), size = Size(tabW, tabH))
                    }
                    isImage -> {
                        drawRect(Krt.Gray1.copy(alpha = alpha), style = Stroke(s))
                        drawLine(
                            Krt.Gray1.copy(alpha = alpha),
                            Offset(size.width * 0.15f, size.height * 0.72f),
                            Offset(size.width * 0.45f, size.height * 0.4f),
                            s,
                        )
                        drawLine(
                            Krt.Gray1.copy(alpha = alpha),
                            Offset(size.width * 0.45f, size.height * 0.4f),
                            Offset(size.width * 0.85f, size.height * 0.72f),
                            s,
                        )
                    }
                    else -> {
                        drawRect(
                            Krt.Gray2.copy(alpha = alpha),
                            topLeft = Offset(size.width * 0.12f, 0f),
                            size = Size(size.width * 0.76f, size.height),
                            style = Stroke(s),
                        )
                        drawLine(
                            Krt.Gray2.copy(alpha = alpha),
                            Offset(size.width * 0.28f, size.height * 0.35f),
                            Offset(size.width * 0.72f, size.height * 0.35f),
                            s,
                        )
                        drawLine(
                            Krt.Gray2.copy(alpha = alpha),
                            Offset(size.width * 0.28f, size.height * 0.6f),
                            Offset(size.width * 0.72f, size.height * 0.6f),
                            s,
                        )
                    }
                }
            },
    )
}

/** The inline new-folder row (SAVE mode): name field + create/cancel; Enter creates, Esc cancels. */
@Composable
private fun NewFolderRow(onCreate: (String) -> Unit, onCancel: () -> Unit, error: Boolean) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(strings.pickerNewFolder) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Krt.Orange.copy(alpha = 0.08f))
            .drawBehind { drawRect(Krt.Orange, size = Size(2.dp.toPx(), size.height)) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Krt.White),
            cursorBrush = SolidColor(Krt.Orange),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            if (name.isNotBlank()) onCreate(name)
                            true
                        }
                        Key.Escape -> {
                            onCancel()
                            true
                        }
                        else -> false
                    }
                },
        )
        if (error) {
            Text(strings.pickerCreateFailed, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
        }
        GhostButton(strings.pickerCreate, onClick = { if (name.isNotBlank()) onCreate(name) })
        GhostButton(strings.cancel, onClick = onCancel)
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray2)
    }
}
