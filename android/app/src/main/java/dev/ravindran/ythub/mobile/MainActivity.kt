@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.ravindran.ythub.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Crimson = Color(0xFFFF173D)
private val Success = Color(0xFF34D399)
private val PagePadding = 20.dp
private val CardShape = RoundedCornerShape(20.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveShare(intent)
        setContent { YTHubApp() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receiveShare(intent) }
    private fun receiveShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("https://[^\\s]+").find(text)?.value.orEmpty()
            if (link.isNotBlank()) DownloadStore.share(link)
        }
    }
}

@Composable private fun YTHubApp() {
    val preferences by DownloadStore.prefs.collectAsStateWithLifecycle()
    val jobs by DownloadStore.items.collectAsStateWithLifecycle()
    val engine by DownloadStore.engine.collectAsStateWithLifecycle()
    val shared by DownloadStore.sharedUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val screenBackdrop = rememberGraphicsLayer()
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    val dark = preferences.theme == "Dark" || (preferences.theme == "System" && isSystemInDarkTheme())
    DisposableEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        onDispose { }
    }
    val scheme = if (dark) darkColorScheme(primary = Crimson, onPrimary = Color.White, background = Color(0xFF0B0C10), surface = Color(0xFF16181F),
        surfaceVariant = Color(0xFF22252E), onSurface = Color(0xFFF8F8FA), onSurfaceVariant = Color(0xFFA3A8B6), outline = Color(0xFF353946))
    else lightColorScheme(primary = Crimson, onPrimary = Color.White, background = Color(0xFFF6F5F3), surface = Color.White,
        surfaceVariant = Color(0xFFEEECEF), onSurface = Color(0xFF171923), onSurfaceVariant = Color(0xFF696B77), outline = Color(0xFFD9D7DD))
    var page by rememberSaveable { mutableStateOf("Home") }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var audio by rememberSaveable { mutableStateOf(true) }
    var quality by rememberSaveable { mutableStateOf(preferences.audioQuality) }
    var playlist by rememberSaveable { mutableStateOf(true) }
    var analyzing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var qualitySheet by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            DownloadStore.savePrefs(DownloadStore.prefs.value.copy(folderUri = uri.toString(), folderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected folder"))
        }.onFailure { error = "Unable to use this folder: ${it.message}" }
    }
    val cookiesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val target = File(context.filesDir, "cookies.txt")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } } ?: error("Could not read cookies")
                check(target.length() <= 2 * 1024 * 1024 && target.readText().contains("Netscape")) { "Choose a Netscape-format cookies.txt file (up to 2 MB)." }
                DownloadStore.savePrefs(DownloadStore.prefs.value.copy(cookiesPath = target.absolutePath))
            } }.onSuccess { notice = "Cookies imported into private app storage" }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(shared) { if (shared.isNotBlank()) { url = shared; page = "Home"; media = null; DownloadStore.consumeShare() } }
    fun act(action: () -> Unit) { runCatching(action).onFailure { error = it.message ?: "This action failed." } }
    fun startDownload() {
        val analyzed = media ?: return
        act {
            DownloadStore.enqueue(analyzed, audio, quality)
            if (Build.VERSION.SDK_INT >= 33 && preferences.notifications) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            media = null; page = "Downloads"; notice = if (preferences.autoStart) "Added to your download queue" else "Added paused · tap resume to start"
        }
    }
    BackHandler(enabled = detail != null || media != null || page !in listOf("Home", "Downloads", "History", "Settings")) {
        if (detail != null) detail = null else if (media != null) media = null else page = "Settings"
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            if (!preferences.onboardingDone) {
                Onboarding(engine) { DownloadStore.savePrefs(preferences.copy(onboardingDone = true)) }
            } else {
                Scaffold(containerColor = scheme.background, bottomBar = {
                    if (media == null && detail == null && page in listOf("Home", "Downloads", "History", "Settings")) {
                        GlassNavigationBar(page, { page = it }, dark, screenBackdrop, contentOrigin)
                    }
                }, topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = PagePadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (media != null || detail != null || page !in listOf("Home", "Downloads", "History", "Settings")) {
                            IconButton(onClick = { if (detail != null) detail = null else if (media != null) media = null else page = "Settings" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Go back") }
                            Text(if (detail != null) "Download details" else if (media != null) if (media!!.playlist) "Playlist details" else "Media details" else page, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        } else {
                            Image(painterResource(R.drawable.ythub_logo), "YTHub", Modifier.size(38.dp))
                            Text("YTHub", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                            if (page == "Home") IconButton(onClick = { page = "Settings" }) { Icon(Icons.Default.Settings, "Settings") }
                        }
                    }
                }) { inset ->
                    AnimatedContent(targetState = when { detail != null -> "detail"; media != null -> "media"; else -> page }, label = "Navigation", modifier = Modifier
                        .padding(top = inset.calculateTopPadding(), bottom = if (media == null && detail == null && page in listOf("Home", "Downloads", "History", "Settings")) 0.dp else inset.calculateBottomPadding())
                        .fillMaxSize().onGloballyPositioned { contentOrigin = it.positionInRoot() }
                        .drawWithContent {
                            screenBackdrop.record { this@drawWithContent.drawContent() }
                            drawLayer(screenBackdrop)
                        }) { destination ->
                        when (destination) {
                            "Home" -> HomeScreen(url, { url = it }, audio, { audio = it; quality = if (it) preferences.audioQuality else preferences.videoQuality }, quality,
                                { qualitySheet = true }, playlist, { playlist = it }, preferences, engine, analyzing,
                                onAnalyze = { scope.launch {
                                    analyzing = true
                                    runCatching { DownloadStore.analyze(url, playlist) }.onSuccess { media = it }.onFailure { error = it.message ?: "Unable to analyze this URL" }
                                    analyzing = false
                                } }, onStorage = { page = "Storage" })
                            "media" -> media?.let { MediaScreen(it, audio, { audio = it; quality = if (it) preferences.audioQuality else preferences.videoQuality }, quality, { qualitySheet = true }, preferences, ::startDownload) }
                            "Downloads" -> DownloadsScreen(jobs, { detail = it }, { id -> act { DownloadStore.pause(id) } }, { id -> act { DownloadStore.resume(id) } }, { confirmCancel = it }, { page = "Home" })
                            "History" -> HistoryScreen(jobs, { detail = it }, { page = "Home" })
                            "detail" -> jobs.find { it.id == detail }?.let { item -> DownloadDetail(item,
                                onPause = { act { DownloadStore.pause(item.id) } }, onResume = { act { DownloadStore.resume(item.id) } }, onCancel = { confirmCancel = item.id },
                                onCopy = { copyText(context, item.media.url); notice = "URL copied" },
                                onOpen = { act { openMedia(context, item.uris.firstOrNull() ?: item.zipUri, item.mime) } },
                                onZip = { act { openMedia(context, item.zipUri, "application/zip") } },
                                onFolder = { act { openFolder(context, item.options.folderUri) } },
                                onRemove = { act { DownloadStore.remove(item.id); detail = null } }) }
                            "Settings" -> SettingsScreen(preferences, engine, onFolder = { folderPicker.launch(null) }, onStorage = { page = "Storage" },
                                onCookies = { cookiesPicker.launch(arrayOf("text/plain", "application/octet-stream")) }, onLogs = { page = "Logs" },
                                onUpdate = { scope.launch { notice = "Checking for an engine update…"; runCatching { DownloadStore.updateEngine() }.onSuccess { notice = it }.onFailure { error = it.message } } })
                            "Storage" -> StorageScreen(jobs, preferences, onFolder = { folderPicker.launch(null) }, onClear = { scope.launch { runCatching { withContext(Dispatchers.IO) { DownloadStore.clearTemporary() } }.onSuccess { notice = "Cleared ${bytesLabel(it)} of temporary files" }.onFailure { error = it.message } } })
                            "Logs" -> LogScreen(jobs)
                        }
                    }
                }
            }
            if (qualitySheet) ModalBottomSheet(onDismissRequest = { qualitySheet = false }, containerColor = scheme.surface) {
                Column(Modifier.fillMaxWidth().padding(PagePadding).navigationBarsPadding()) {
                    Text(if (audio) "MP3 audio quality" else "Video quality", style = MaterialTheme.typography.headlineSmall)
                    Text(if (audio) "Higher bitrate uses more storage." else "The engine uses the best available format up to your limit.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    val choices = if (audio) listOf("320", "256", "192", "128", "best") else listOf("best", "2160", "1440", "1080", "720", "480")
                    choices.forEach { option -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { quality = option; qualitySheet = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option == quality, onClick = { quality = option; qualitySheet = false })
                        Text(qualityLabel(audio, option), modifier = Modifier.padding(start = 8.dp))
                    } }
                }
            }
            if (error != null) AlertDialog(onDismissRequest = { error = null }, icon = { Icon(Icons.Default.ErrorOutline, null, tint = Crimson) }, title = { Text("Something needs attention") },
                text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { Text(error!!) } }, confirmButton = { TextButton(onClick = { error = null }) { Text("Got it") } })
            if (notice != null) {
                LaunchedEffect(notice) { kotlinx.coroutines.delay(3500); notice = null }
                Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 88.dp, start = 20.dp, end = 20.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(shape = RoundedCornerShape(14.dp), color = scheme.inverseSurface, shadowElevation = 8.dp) { Text(notice!!, color = scheme.inverseOnSurface, modifier = Modifier.padding(16.dp)) }
                }
            }
            if (confirmCancel != null) AlertDialog(onDismissRequest = { confirmCancel = null }, title = { Text("Cancel this download?") },
                text = { Text("Downloaded parts are kept so you can retry later.") }, dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep downloading") } },
                confirmButton = { TextButton(onClick = { act { DownloadStore.cancel(confirmCancel!!) }; confirmCancel = null }) { Text("Cancel download") } })
        }
    }
}

@Composable private fun Onboarding(engine: String, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(180.dp).background(Brush.radialGradient(listOf(Crimson.copy(alpha = .22f), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ythub_logo), "YTHub play and download logo", Modifier.size(130.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Your media.\nYour way.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("Welcome to YTHub", fontSize = 18.sp, modifier = Modifier.padding(top = 20.dp))
        Text("Download. Convert. Manage.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("MP3", "MP4", "Playlists", "ZIP").forEach { BadgeText(it) } }
        Spacer(Modifier.weight(1f))
        Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (engine.contains("ready")) Icons.Default.CheckCircle else Icons.Default.Tune, null, tint = if (engine.contains("ready")) Success else Crimson); Text(engine, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Get started", Icons.Default.ArrowForward, onStart, enabled = engine.contains("ready"))
        Text("Your YouTube Media Hub", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
    }
}

@Composable private fun HomeScreen(url: String, onUrl: (String) -> Unit, audio: Boolean, onAudio: (Boolean) -> Unit, quality: String, onQuality: () -> Unit,
    playlist: Boolean, onPlaylist: (Boolean) -> Unit, prefs: Preferences, engine: String, busy: Boolean, onAnalyze: () -> Unit, onStorage: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding).padding(bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text("Make it yours.", style = MaterialTheme.typography.headlineLarge)
            Text("Save the video. Keep the soundtrack.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        CardBlock {
            Label("YOUTUBE LINK")
            OutlinedTextField(value = url, onValueChange = onUrl, placeholder = { Text("Paste a YouTube URL", fontSize = 14.sp) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), leadingIcon = { Icon(Icons.Default.Link, null) },
                trailingIcon = { IconButton(onClick = { if (url.isNotBlank()) onUrl("") else {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    onUrl(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                } }) { Icon(if (url.isBlank()) Icons.Default.ContentPaste else Icons.Default.Close, if (url.isBlank()) "Paste from clipboard" else "Clear URL") } })
            PrimaryButton(if (busy) "Analyzing your link…" else "Analyze URL", Icons.Default.Search, onAnalyze, !busy && url.isNotBlank() && engine.contains("ready"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("SAVE AS")
            FormatSelector(audio, onAudio)
            ChoiceRow("Quality", qualityLabel(audio, quality), Icons.Default.HighQuality, onQuality)
        }
        CardBlock {
            Label("PLAYLIST OPTIONS")
            ToggleRow("Download playlist", "Analyze the full playlist", playlist, onPlaylist)
            ToggleRow("Create ZIP", "One archive, all your media", prefs.autoZip) { DownloadStore.savePrefs(prefs.copy(autoZip = it)) }
            ToggleRow("Preserve order", "Number files in playlist order", prefs.preserveOrder) { DownloadStore.savePrefs(prefs.copy(preserveOrder = it)) }
        }
        Row(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onStorage).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FolderOpen, null, tint = Crimson)
            Column(Modifier.padding(start = 12.dp).weight(1f)) { Text("Saved on your phone", fontWeight = FontWeight.SemiBold); Text(prefs.folderName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Default.ChevronRight, "Storage")
        }
        Text("Analyze a link to preview the media and start downloading.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun MediaScreen(media: MediaInfo, audio: Boolean, onAudio: (Boolean) -> Unit, quality: String, onQuality: () -> Unit, prefs: Preferences, onDownload: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(CardShape).background(MaterialTheme.colorScheme.surface)) {
                    Thumbnail(media.thumbnail, Modifier.fillMaxSize())
                    if (media.duration > 0) Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) { BadgeText(durationLabel(media.duration)) }
                }
            }
            item {
                Text(media.title, style = MaterialTheme.typography.headlineSmall)
                Text(media.channel, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (media.playlist) "${media.count} items · ${media.entries.size} loaded${if (media.count > 500) " · first 500 available" else ""}" else listOfNotNull(media.views.takeIf { it > 0 }?.let { "%,d views".format(it) }, media.uploadDate.takeIf { it.isNotBlank() }?.let { "Uploaded $it" }).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            item { FormatSelector(audio, onAudio) }
            item { ChoiceRow("Quality", qualityLabel(audio, quality), Icons.Default.HighQuality, onQuality) }
            if (media.qualities.isNotEmpty()) item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { media.qualities.forEach { BadgeText("${it}p") } } }
            if (media.playlist) {
                item { CardBlock { ToggleRow("Preserve order", "Keep the original playlist sequence", prefs.preserveOrder) { DownloadStore.savePrefs(prefs.copy(preserveOrder = it)) }; ToggleRow("Create ZIP", "Archive after the downloads finish", prefs.autoZip) { DownloadStore.savePrefs(prefs.copy(autoZip = it)) } } }
                item { Label("PLAYLIST ITEMS") }
                items(media.entries.withIndex().toList(), key = { "${it.index}:${it.value.url}" }) { (index, entry) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(index + 1), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                        Thumbnail(entry.thumbnail, Modifier.width(72.dp).height(48.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); if (entry.duration > 0) Text(durationLabel(entry.duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) { Box(Modifier.padding(horizontal = PagePadding, vertical = 16.dp).navigationBarsPadding()) {
            PrimaryButton(if (media.playlist) "Download playlist" else if (audio) "Download audio" else "Download video", Icons.Default.Download, onDownload)
        } }
    }
}

@Composable private fun DownloadsScreen(jobs: List<DownloadItem>, onDetail: (String) -> Unit, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit, onHome: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("Active") }
    val filtered = jobs.filter { when (filter) { "Completed" -> it.state == "Completed"; "Failed" -> it.state in listOf("Failed", "Cancelled"); else -> !it.terminal } }
    Column(Modifier.fillMaxSize().padding(horizontal = PagePadding)) {
        Text("Downloads", style = MaterialTheme.typography.headlineLarge)
        Text("Everything in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        FilterTabs(listOf("Active", "Completed", "Failed"), filter) { filter = it }
        if (filtered.isEmpty()) EmptyState(if (filter == "Active") "Your queue is clear" else "No ${filter.lowercase()} downloads", "Paste a YouTube link to save your next favorite.", onHome)
        else LazyColumn(contentPadding = PaddingValues(top = 20.dp, bottom = 132.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(filtered, key = { it.id }) { item -> DownloadCard(item, { onDetail(item.id) }, { onPause(item.id) }, { onResume(item.id) }, { onCancel(item.id) }) }
        }
    }
}

@Composable private fun DownloadCard(item: DownloadItem, onDetail: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable(onClick = onDetail)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(item.media.thumbnail, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(item.media.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${if (item.audio) "MP3" else "Video"} · ${qualityLabel(item.audio, item.quality)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(item.state, color = if (item.state == "Completed") Success else Crimson, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${item.progress.toInt()}%", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Crimson, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Text(if (item.error.isNotEmpty()) "Could not finish · tap for details" else item.line, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            if (!item.terminal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.eta > 0) Text("ETA ${durationLabel(item.eta)}", modifier = Modifier.weight(1f).align(Alignment.CenterVertically), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = if (item.state in listOf("Paused", "Interrupted")) onResume else onPause) { Icon(if (item.state in listOf("Paused", "Interrupted")) Icons.Default.PlayArrow else Icons.Default.Pause, if (item.state in listOf("Paused", "Interrupted")) "Resume download" else "Pause download") }
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel download", tint = Crimson) }
            }
        }
    }
}

@Composable private fun HistoryScreen(jobs: List<DownloadItem>, onDetail: (String) -> Unit, onHome: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val filtered = jobs.filter { it.terminal && it.media.title.contains(search, true) && when (filter) { "Audio" -> it.audio; "Video" -> !it.audio; "Playlists" -> it.media.playlist; else -> true } }
    Column(Modifier.fillMaxSize().padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your library", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(search, { search = it }, placeholder = { Text("Search your history") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp))
        FilterTabs(listOf("All", "Audio", "Video", "Playlists"), filter) { filter = it }
        if (filtered.isEmpty()) EmptyState("Nothing here yet", "Your finished downloads will appear here.", onHome)
        else LazyColumn(contentPadding = PaddingValues(bottom = 132.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(filtered, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().clip(CardShape).background(MaterialTheme.colorScheme.surface).clickable { onDetail(item.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(item.media.thumbnail, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${if (item.media.playlist) "Playlist" else if (item.audio) "Audio" else "Video"} · ${bytesLabel(item.bytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Text("${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(item.created))} · ${item.state}", color = if (item.state == "Completed") Success else Crimson, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Icon(Icons.Default.ChevronRight, "Download actions", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable private fun DownloadDetail(item: DownloadItem, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onCopy: () -> Unit, onOpen: () -> Unit, onZip: () -> Unit, onFolder: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Thumbnail(item.media.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(CardShape))
        if (item.state == "Completed") Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Success); Text(if (item.media.playlist) "Playlist complete" else "Download complete", color = Success, modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold) }
        Text(item.media.title, style = MaterialTheme.typography.headlineSmall)
        DownloadCard(item, {}, onPause, onResume, onCancel)
        CardBlock {
            DetailRow("Format", if (item.audio) "MP3 audio" else "Video")
            DetailRow("Quality", qualityLabel(item.audio, item.quality))
            DetailRow("File size", if (item.bytes > 0) bytesLabel(item.bytes) else "Available after download")
            DetailRow("Destination", item.options.folderName)
            DetailRow("State", item.state)
            if (item.media.playlist) DetailRow("Playlist results", "${item.completedCount} downloaded · ${item.failedCount} failed")
            DetailRow("Source URL", item.media.url)
        }
        if (item.error.isNotBlank()) CardBlock { Label("DOWNLOAD ERROR"); Text(item.error, color = Crimson, fontSize = 13.sp) }
        if (item.uris.isNotEmpty()) PrimaryButton("Open file", Icons.Default.PlayArrow, onOpen)
        if (item.zipUri.isNotBlank()) PrimaryButton("Open ZIP", Icons.Default.FolderZip, onZip)
        if (item.state in listOf("Failed", "Cancelled", "Paused", "Interrupted")) PrimaryButton(if (item.state in listOf("Paused", "Interrupted")) "Resume download" else "Retry download", Icons.Default.Refresh, onResume)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Text("Copy URL", Modifier.padding(start = 8.dp)) }
            OutlinedButton(onClick = onFolder, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Text("Open folder", Modifier.padding(start = 8.dp)) }
        }
        if (item.terminal || item.state in listOf("Paused", "Interrupted")) TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteOutline, null); Text("Remove from history", Modifier.padding(start = 8.dp)) }
    }
}

@Composable private fun SettingsScreen(prefs: Preferences, engine: String, onFolder: () -> Unit, onStorage: () -> Unit, onCookies: () -> Unit, onLogs: () -> Unit, onUpdate: () -> Unit) {
    var proxyDialog by remember { mutableStateOf(false) }
    var proxy by remember { mutableStateOf(prefs.proxy) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding).padding(bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Make it work for you.", style = MaterialTheme.typography.headlineSmall)
        SettingsGroup("DOWNLOADS") {
            ChoiceRow("Save location", prefs.folderName, Icons.Default.FolderOpen, onFolder)
            SelectSetting("Concurrent downloads", prefs.concurrency.toString(), listOf("1", "2", "3")) { DownloadStore.savePrefs(prefs.copy(concurrency = it.toInt())) }
            ToggleRow("Start automatically", "Begin as soon as you add to queue", prefs.autoStart) { DownloadStore.savePrefs(prefs.copy(autoStart = it)) }
        }
        SettingsGroup("FORMAT & QUALITY") {
            SelectSetting("Audio quality", prefs.audioQuality, listOf("320", "256", "192", "128", "best"), { qualityLabel(true, it) }) { DownloadStore.savePrefs(prefs.copy(audioQuality = it)) }
            SelectSetting("Video quality", prefs.videoQuality, listOf("best", "2160", "1440", "1080", "720", "480"), { qualityLabel(false, it) }) { DownloadStore.savePrefs(prefs.copy(videoQuality = it)) }
        }
        SettingsGroup("PLAYLISTS") {
            ToggleRow("Preserve ordering", "Add the item number to filenames", prefs.preserveOrder) { DownloadStore.savePrefs(prefs.copy(preserveOrder = it)) }
            ToggleRow("Create ZIP automatically", "An archive when the playlist finishes", prefs.autoZip) { DownloadStore.savePrefs(prefs.copy(autoZip = it)) }
            ToggleRow("Keep individual media files", "Save the media alongside the ZIP", prefs.keepFolder) { DownloadStore.savePrefs(prefs.copy(keepFolder = it)) }
        }
        SettingsGroup("APPEARANCE & NOTIFICATIONS") {
            SelectSetting("Theme", prefs.theme, listOf("Dark", "Light", "System")) { DownloadStore.savePrefs(prefs.copy(theme = it)) }
            ToggleRow("Completion notifications", "Know when your media is ready", prefs.notifications) { DownloadStore.savePrefs(prefs.copy(notifications = it)) }
        }
        SettingsGroup("STORAGE") { ChoiceRow("Storage overview", "Media, ZIPs and temporary files", Icons.Default.Storage, onStorage) }
        SettingsGroup("MEDIA ENGINE") {
            Text(engine, color = if (engine.contains("ready")) Success else Crimson, fontSize = 12.sp)
            ChoiceRow("yt-dlp", DownloadStore.engineVersion(), Icons.Default.SystemUpdate, onUpdate)
            DetailRow("FFmpeg / Python / QuickJS", "Bundled · Android engine 0.18.1")
            SelectSetting("Network retries", prefs.retries.toString(), (0..5).map { it.toString() }) { DownloadStore.savePrefs(prefs.copy(retries = it.toInt())) }
            ChoiceRow("Cookies file", if (prefs.cookiesPath.isBlank()) "Optional · Netscape format" else "Imported into private storage", Icons.Default.Cookie, onCookies)
            if (prefs.cookiesPath.isNotBlank()) TextButton(onClick = { DownloadStore.savePrefs(prefs.copy(cookiesPath = "")) }) { Text("Remove cookies") }
            ChoiceRow("Proxy", prefs.proxy.ifBlank { "No proxy" }, Icons.Default.Language) { proxyDialog = true }
            ChoiceRow("Download logs", "Review errors and engine output", Icons.Default.Notes, onLogs)
        }
        Text("YTHub for Android · 1.0.2\nDownload. Convert. Manage.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
    if (proxyDialog) AlertDialog(onDismissRequest = { proxyDialog = false }, title = { Text("Proxy") }, text = {
        OutlinedTextField(proxy, { proxy = it }, label = { Text("HTTPS or SOCKS proxy URL") }, placeholder = { Text("http://host:port") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { DownloadStore.savePrefs(prefs.copy(proxy = proxy.trim())); proxyDialog = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { proxyDialog = false }) { Text("Cancel") } })
}

@Composable private fun StorageScreen(jobs: List<DownloadItem>, prefs: Preferences, onFolder: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    var temporary by remember { mutableLongStateOf(0) }
    LaunchedEffect(jobs) { temporary = withContext(Dispatchers.IO) { DownloadStore.temporaryBytes() } }
    val stats = StatFs(context.filesDir.absolutePath)
    val total = stats.totalBytes
    val free = stats.availableBytes
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Room for your favorites.", style = MaterialTheme.typography.headlineSmall)
        CardBlock {
            Label("DEVICE STORAGE")
            Text(bytesLabel(free), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("free of ${bytesLabel(total)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (1f - free.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), color = Crimson, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
        CardBlock {
            Label("YTHUB DOWNLOAD HISTORY")
            DetailRow("Audio", bytesLabel(jobs.filter { it.audio && it.state == "Completed" }.sumOf { it.bytes }))
            DetailRow("Video", bytesLabel(jobs.filter { !it.audio && it.state == "Completed" }.sumOf { it.bytes }))
            DetailRow("ZIP archives", "${jobs.count { it.zipUri.isNotBlank() }} saved")
            DetailRow("Temporary files", bytesLabel(temporary))
            Text("Media totals come from download history. Files removed outside YTHub may still be counted.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardBlock {
            Label("DOWNLOAD LOCATION")
            Text(prefs.folderName, fontWeight = FontWeight.SemiBold)
            PrimaryButton("Choose folder", Icons.Default.FolderOpen, onFolder)
            if (prefs.folderUri.isNotBlank()) TextButton(onClick = { DownloadStore.savePrefs(prefs.copy(folderUri = "", folderName = "Downloads / YTHub")) }) { Text("Use default Downloads folder") }
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Default.CleaningServices, null); Text("Clear temporary files", Modifier.padding(start = 10.dp)) }
        Text("Paused and interrupted downloads keep their partial files so they can resume.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun LogScreen(jobs: List<DownloadItem>) {
    LazyColumn(contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (jobs.isEmpty()) item { Text("No download logs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(jobs, key = { it.id }) { item -> CardBlock {
            Text(item.media.title, fontWeight = FontWeight.Bold)
            Text("${item.state} · ${item.id}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.error.ifBlank { item.line }, fontSize = 12.sp)
        } }
    }
}

@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Label(text: String) { Text(text, fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Crimson)) {
        Icon(icon, null, Modifier.size(20.dp)); Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
    }
}
@Composable private fun BadgeText(text: String) { Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) { Text(text, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) } }
@Composable private fun FormatSelector(audio: Boolean, onAudio: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(true to "Audio · MP3", false to "Video · MP4").forEach { (isAudio, label) ->
            val selected = isAudio == audio
            Surface(onClick = { onAudio(isAudio) }, color = if (selected) Crimson.copy(alpha = .13f) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if (selected) Crimson else MaterialTheme.colorScheme.outline), modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isAudio) Icons.Default.MusicNote else Icons.Default.Videocam, null, Modifier.size(22.dp), tint = if (selected) Crimson else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
@Composable private fun ChoiceRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Crimson)
        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Crimson, checkedThumbColor = Color.White))
    }
}
@Composable private fun FilterTabs(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { option ->
        Surface(onClick = { onSelect(option) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp), color = if (selected == option) Crimson else MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) { Text(option, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected == option) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } }
}
@Composable private fun Thumbnail(url: String, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url.isBlank()) Image(painterResource(R.drawable.ythub_logo), "YTHub", Modifier.size(40.dp))
        else AsyncImage(model = url, contentDescription = "Media thumbnail", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = painterResource(R.drawable.ythub_logo))
    }
}
@Composable private fun EmptyState(title: String, text: String, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.ythub_logo), "YTHub", Modifier.size(76.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        PrimaryButton("Find something to save", Icons.Default.Link, onHome)
    }
}
@Composable private fun DetailRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
}
@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Label(title); CardBlock(content) }
}
@Composable private fun SelectSetting(title: String, value: String, options: List<String>, display: (String) -> String = { it }, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ChoiceRow(title, display(value), Icons.Default.Tune) { open = true }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.padding(PagePadding).navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
            options.forEach { option -> Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onSelect(option); open = false }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(option == value, { onSelect(option); open = false }); Text(display(option), modifier = Modifier.padding(start = 8.dp))
            } }
        }
    }
}
private fun copyText(context: Context, url: String) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("YTHub source URL", url)) }
private fun openMedia(context: Context, value: String, mime: String) {
    require(value.isNotBlank()) { "This file is not available yet." }
    val uri = Uri.parse(value)
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.close() ?: error("This saved file has been removed or is unavailable.")
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
}
private fun openFolder(context: Context, folder: String) {
    val uri = if (folder.isBlank()) Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FYTHub") else DocumentFile.fromTreeUri(context, Uri.parse(folder))?.uri ?: Uri.parse(folder)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
    else context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra("android.provider.extra.INITIAL_URI", uri))
}
