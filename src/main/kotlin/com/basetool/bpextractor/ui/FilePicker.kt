package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------
// Model + pure logic (no Compose) — unit-tested in FilePickerTest.
// ---------------------------------------------------------------------------

/** What the picker selects: an existing directory, or a (possibly new) file to save. */
enum class PickerMode { FOLDER, SAVE_FILE }

/** One row in the browser. */
data class PickerEntry(val file: File, val isDirectory: Boolean)

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
 *  - [dir] == null -> the drive roots ("Dieser PC").
 *  - FOLDER        -> sub-directories only.
 *  - SAVE_FILE     -> sub-directories plus files ending in `.[extension]`.
 *
 * Hidden entries are skipped; results are sorted directories-first, then case-insensitive by
 * name. Returns [Listing.Denied] when the directory can't be enumerated — it never throws and
 * never writes, so a bad path is harmless.
 */
fun listChildren(dir: File?, mode: PickerMode, extension: String): Listing {
    if (dir == null) {
        return Listing.Ok(File.listRoots().orEmpty().map { PickerEntry(it, isDirectory = true) })
    }
    val children = dir.listFiles() ?: return Listing.Denied
    val ext = extension.removePrefix(".").lowercase()
    val entries = children.asSequence()
        .filterNot { it.isHidden }
        .filter { f -> f.isDirectory || (mode == PickerMode.SAVE_FILE && matchesExtension(f.name, ext)) }
        .map { PickerEntry(it, it.isDirectory) }
        .sortedWith(compareByDescending<PickerEntry> { it.isDirectory }.thenBy { it.file.name.lowercase() })
        .toList()
    return Listing.Ok(entries)
}

private fun matchesExtension(name: String, extLower: String): Boolean =
    extLower.isEmpty() || name.lowercase().endsWith(".$extLower")

/** Append `.[extension]` to [name] unless it already ends with it (case-insensitive). */
fun ensureExtension(name: String, extension: String): String {
    val ext = extension.removePrefix(".")
    if (ext.isEmpty()) return name
    return if (name.lowercase().endsWith(".${ext.lowercase()}")) name else "$name.$ext"
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

/** Result of resolving a typed/pasted address-bar path: a target [dir] (null = roots) + optional [fileName]. */
data class TypedPath(val dir: File?, val fileName: String?)

/**
 * Resolve a typed/pasted [text] into a target directory (and, in SAVE_FILE mode, a filename).
 * Strips surrounding quotes — Windows Explorer's "Copy as path" wraps the path in `"`. Returns a
 * [TypedPath] with `dir == null` for empty input (drive roots), or null if it can't resolve to an
 * existing directory. Read-only — never creates or writes anything.
 */
fun resolveTypedPath(text: String, mode: PickerMode): TypedPath? {
    val raw = text.trim().removeSurrounding("\"").trim()
    if (raw.isEmpty()) return TypedPath(null, null)
    val f = File(raw)
    return when {
        f.isDirectory -> TypedPath(f, null)
        f.parentFile?.isDirectory == true ->
            TypedPath(f.parentFile, if (mode == PickerMode.SAVE_FILE && f.name.isNotBlank()) f.name else null)
        else -> null
    }
}

/** Roots report an empty name (`File("C:\\").name == ""`) — fall back to the path so they show. */
private fun displayName(entry: PickerEntry): String = entry.file.name.ifBlank { entry.file.path }

// ---------------------------------------------------------------------------
// Composable UI
// ---------------------------------------------------------------------------

/**
 * KRT-styled, in-app file/folder browser shown as a modal overlay. Pure Compose — no OS dialog —
 * so it matches the HUD instead of the legacy Win32 chooser. Browsing is read-only (`java.io.File`
 * listing); the caller's path text field stays as a typed-path fallback for anything this can't reach.
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
    var currentDir by remember { mutableStateOf(initialDirectory(initialPath, mode)) }
    var fileName by remember {
        mutableStateOf(if (mode == PickerMode.SAVE_FILE) initialFileName(initialPath, "blueprints.$extension") else "")
    }
    var listing by remember { mutableStateOf<Listing?>(null) } // null = (re)loading
    var pathText by remember { mutableStateOf(currentDir?.absolutePath ?: "") }
    var pathError by remember { mutableStateOf(false) }

    // List off the UI thread so a slow/network directory never janks composition; also re-sync the
    // editable address bar to wherever navigation lands.
    LaunchedEffect(currentDir) {
        pathText = currentDir?.absolutePath ?: ""
        pathError = false
        listing = null
        listing = withContext(Dispatchers.IO) { listChildren(currentDir, mode, extension) }
    }

    // Commit a typed/pasted path from the address bar (Enter / "Gehe zu"). Read-only resolution.
    fun navigateTo() {
        val resolved = resolveTypedPath(pathText, mode)
        if (resolved == null) {
            pathError = true
        } else {
            currentDir = resolved.dir
            resolved.fileName?.let { fileName = it }
            pathError = false
        }
    }

    val swallow = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Krt.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss, // click outside the panel = cancel
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .hudBox(fill = Krt.Gray4)
                .clickable(interactionSource = swallow, indication = null, onClick = {}) // swallow panel clicks
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                    modifier = Modifier.weight(1f),
                )
                CloseButton(onDismiss)
            }

            Spacer(Modifier.height(14.dp))

            // Address bar: jump to roots, go up, or type/paste a path and commit it (Enter / "Gehe zu").
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Dieser PC", enabled = currentDir != null, onClick = { currentDir = null })
                GhostButton("Hoch", enabled = currentDir != null, onClick = { currentDir = currentDir?.parentFile })
                KrtTextField(
                    value = pathText,
                    onValueChange = { pathText = it; pathError = false },
                    placeholder = "Pfad einfügen und Enter …",
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                navigateTo()
                                true
                            } else {
                                false
                            }
                        },
                )
                GhostButton("Gehe zu", onClick = { navigateTo() })
            }
            if (pathError) {
                Spacer(Modifier.height(6.dp))
                Text("Ordner nicht gefunden.", style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
            }

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Krt.SurfaceInput)
                    .border(1.dp, Krt.Gray3),
            ) {
                when (val l = listing) {
                    null -> CenteredNote("Lädt…")
                    is Listing.Denied -> CenteredNote("Kein Zugriff auf diesen Ordner.")
                    is Listing.Ok -> if (l.entries.isEmpty()) {
                        CenteredNote(
                            if (mode == PickerMode.FOLDER) "Keine Unterordner hier."
                            else "Keine Ordner oder .$extension-Dateien hier.",
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(l.entries, key = { it.file.path }) { entry ->
                                PickerRow(entry) {
                                    if (entry.isDirectory) currentDir = entry.file else fileName = entry.file.name
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (mode == PickerMode.SAVE_FILE) {
                FieldLabel("Dateiname")
                KrtTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = "blueprints.$extension",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
            }

            val canConfirm = currentDir != null && (mode == PickerMode.FOLDER || fileName.isNotBlank())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CtaButton(
                    confirmLabel,
                    enabled = canConfirm,
                    onClick = {
                        val dir = currentDir
                        if (dir != null) {
                            val chosen = if (mode == PickerMode.FOLDER) dir.absolutePath
                            else File(dir, ensureExtension(fileName.trim(), extension)).absolutePath
                            onConfirm(chosen)
                        }
                    },
                )
                GhostButton("Abbrechen", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun PickerRow(entry: PickerEntry, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) Krt.Gray3 else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(if (entry.isDirectory) Krt.Orange else Krt.Gray2)
        Text(
            displayName(entry),
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isDirectory) Krt.Gray1 else Krt.Gray2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(32.dp)
            .border(1.dp, if (hovered) Krt.Orange else Krt.Gray3)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", style = MaterialTheme.typography.labelLarge, color = if (hovered) Krt.Orange else Krt.Gray1)
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray2)
    }
}
