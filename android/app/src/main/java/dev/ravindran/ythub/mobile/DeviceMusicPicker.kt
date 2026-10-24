@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.ravindran.ythub.mobile

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as AudioMedia
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android's shared audio index, including music saved by other apps. No copying or online lookup. */
internal object DeviceMusicCatalog {
    val permission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun query(context: Context): List<LocalTrack> {
        val columns = arrayOf(AudioMedia._ID, AudioMedia.DISPLAY_NAME, AudioMedia.TITLE, AudioMedia.ARTIST, AudioMedia.ALBUM, AudioMedia.DURATION, AudioMedia.SIZE)
        val selection = buildList {
            add("${AudioMedia.DURATION} > 0")
            if (Build.VERSION.SDK_INT >= 29) add("${AudioMedia.IS_PENDING} = 0")
            if (Build.VERSION.SDK_INT >= 30) add("${AudioMedia.IS_TRASHED} = 0")
        }.joinToString(" AND ")
        return buildList {
            context.contentResolver.query(AudioMedia.EXTERNAL_CONTENT_URI, columns, selection, null, "${AudioMedia.TITLE} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(AudioMedia.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString()
                    val filename = cursor.getString(1).orEmpty()
                    add(LocalTrack(java.util.UUID.nameUUIDFromBytes(uri.toByteArray()).toString(), uri,
                        cursor.getString(2).orEmpty().ifBlank { filename.substringBeforeLast('.') },
                        cursor.getString(3).orEmpty().takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist",
                        cursor.getString(4).orEmpty().takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown album",
                        filename, cursor.getLong(5), cursor.getLong(6), format = filename.substringAfterLast('.', "audio").uppercase(), folder = "Phone audio"))
                }
            }
        }
    }
}

@Composable internal fun DeviceMusicPicker(playlistId: String?, onDismiss: () -> Unit, onChooseFiles: () -> Unit) {
    val context = LocalContext.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val library by MusicRepository.state.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(DeviceMusicCatalog.allowed(context)) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf(emptyList<LocalTrack>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var search by remember { mutableStateOf("") }
    val query = search.trim()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> access = granted; revision++ }
    LaunchedEffect(Unit) { if (!access) permission.launch(DeviceMusicCatalog.permission) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { access = DeviceMusicCatalog.allowed(context); revision++ } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(access, context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { revision++ } }
        if (access) runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }.onFailure { access = false; error = "Music access changed. Allow access to browse again." }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(access, revision) {
        if (!access) { songs = emptyList(); return@LaunchedEffect }
        delay(250)
        loading = true; error = null
        try { songs = withContext(Dispatchers.IO) { DeviceMusicCatalog.query(context) } }
        catch (e: Exception) { error = "Could not read phone music. Check music access and try again." }
        finally { loading = false }
    }
    val visible by produceState(songs, songs, query) { value = withContext(Dispatchers.Default) { songs.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || it.filename.contains(query, true) } } }
    val already = remember(library.tracks, library.playlists, playlistId) {
        val ids = library.playlists.firstOrNull { it.id == playlistId }?.tracks?.toSet().orEmpty()
        library.tracks.filter { it.id in ids }.map { it.uri }.toSet()
    }
    GlassSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Music on this phone", style = MaterialTheme.typography.headlineSmall)
            if (!keyboardOpen) Text("Choose music already saved on your phone. New downloads appear here automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!access) {
                Text("Allow music and audio access to browse your phone’s songs here.")
                Button(onClick = { permission.launch(DeviceMusicCatalog.permission) }) { Text("Allow music access") }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open app permissions") }
                Spacer(Modifier.weight(1f))
            } else {
                OutlinedTextField(search, { search = it }, label = { Text("Search phone music") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text("Try again") } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("phone-music-list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (visible.isEmpty() && !loading && error == null) item { Text(if (query.isBlank()) "No audio found yet. You can also choose a file below." else "No matching songs.", Modifier.padding(vertical = 20.dp)) }
                    items(visible, key = { it.uri }) { song ->
                        val added = song.uri in already
                        val checked = added || song.uri in selected
                        fun toggle() { selected = if (song.uri in selected) selected - song.uri else selected + song.uri }
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = !added && !busy) { toggle() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, { toggle() }, enabled = !added && !busy)
                                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (added) "Already in playlist" else "${song.artist} · ${durationLabel(song.durationMs / 1000)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Button(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val ids = MusicRepository.importAudio(songs.filter { it.uri in selected && it.uri !in already }.map { Uri.parse(it.uri) })
                            if (ids.isNotEmpty()) { if (playlistId != null) MusicRepository.addToPlaylist(playlistId, ids); onDismiss() }
                            else error = "Those files could not be opened. Refresh or choose another song."
                        } finally { busy = false }
                    }
                }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (playlistId == null) "Add ${selected.size} to library" else "Add ${selected.size} to playlist") }
            }
            if (!keyboardOpen) TextButton(onClick = onChooseFiles, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Choose a file instead") }
        }
    }
}
