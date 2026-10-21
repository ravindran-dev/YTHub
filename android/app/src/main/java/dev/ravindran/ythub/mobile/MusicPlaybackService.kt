package dev.ravindran.ythub.mobile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The service owns the sole engine; UI and system controls share its MediaSession. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restored = false
    private var lastSaved = 0L
    private var counted = ""
    private var heardMs = 0L
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (MusicRules.sleepExpired(MusicPlayback.deadline, now)) MusicPlayback.cancelTimer().also { player.pause() }
            if (player.isPlaying) {
                heardMs += 500
                val id = player.currentMediaItem?.mediaId.orEmpty()
                if (id != counted && heardMs >= 5000) { MusicRepository.played(id); counted = id }
            }
            MusicPlayback.publish(player, eq, bass)
            if (now - lastSaved >= 5000) { save(); lastSaved = now }
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        session = MediaSession.Builder(this, player).setSessionActivity(PendingIntent.getActivity(this, 700, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        MusicPlayback.engineSettings = { applySettings() }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                MusicPlayback.lastPlayIntent = "$playWhenReady reason=$reason settings=${MusicRepository.state.value.settings} timer=${MusicPlayback.sleepMode}"
                android.util.Log.d("YTHubPlayback", MusicPlayback.lastPlayIntent)
            }
            override fun onEvents(player: Player, events: Player.Events) { MusicPlayback.publish(player, eq, bass) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                heardMs = 0; counted = ""
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    if (MusicPlayback.sleepMode == "End of track" || !MusicRepository.state.value.settings.autoNext) {
                        player.pause(); MusicPlayback.cancelTimer()
                    }
                }
                save()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && MusicPlayback.sleepMode == "End of playlist" && oldPosition.mediaItemIndex >= 0 && player.currentTimeline.getNextWindowIndex(oldPosition.mediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled) == C.INDEX_UNSET) {
                    player.pause(); MusicPlayback.cancelTimer()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && MusicPlayback.sleepMode in listOf("End of playlist", "End of track")) { player.pause(); MusicPlayback.cancelTimer() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) save() }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { releaseEffects(); if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                eq = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
                bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull(); applySettings()
            } }
            override fun onPlayerError(error: PlaybackException) { MusicPlayback.lastPlaybackError = "Cannot play this file: ${error.localizedMessage}"; MusicPlayback.error.value = "Cannot play this file: ${error.localizedMessage}. Rescan the library to check access."; save() }
        })
        scope.launch {
        MusicRepository.ready.first { it }
        val saved = MusicRepository.state.value
        if (saved.settings.restoreTrack && player.mediaItemCount == 0) {
            val (queue, index) = MusicRules.restore(saved.queue, saved.queueIndex, saved.tracks)
            val byId = saved.tracks.associateBy { it.id }
            val media = queue.mapNotNull { id -> byId[id]?.asMediaItem() }
            if (media.isNotEmpty()) { player.setMediaItems(media, index, if (saved.settings.rememberPosition && saved.queue.getOrNull(saved.queueIndex) == queue.getOrNull(index)) saved.position.coerceAtLeast(0) else 0); player.prepare() }
        }
        player.repeatMode = saved.repeat.coerceIn(0, 2); player.shuffleModeEnabled = saved.shuffle
        restored = true
        applySettings(); handler.post(tick)
        }
    }
    private fun applySettings() {
        val settings = MusicRepository.state.value.settings
        player.setPlaybackSpeed(settings.speed)
        runCatching {
            eq?.let { effect ->
                check(effect.setEnabled(settings.equalizer) == android.media.audiofx.AudioEffect.SUCCESS) { "Equalizer could not be enabled on this output" }
                val preset = (0 until effect.numberOfPresets.toInt()).firstOrNull { effect.getPresetName(it.toShort()).equals(settings.preset, true) }
                if (settings.preset == "Flat") (0 until effect.numberOfBands.toInt()).forEach { effect.setBandLevel(it.toShort(), 0) }
                else if (preset != null) effect.usePreset(preset.toShort())
                else if (settings.preset == "Custom") settings.bands.take(effect.numberOfBands.toInt()).forEachIndexed { i, level -> effect.setBandLevel(i.toShort(), level.coerceIn(effect.bandLevelRange[0].toInt(), effect.bandLevelRange[1].toInt()).toShort()) }
            }
            bass?.let { check(it.setEnabled(settings.bass > 0) == android.media.audiofx.AudioEffect.SUCCESS) { "Bass boost is unavailable on this output" }; if (it.strengthSupported) it.setStrength(settings.bass.coerceIn(0, 1000).toShort()) }
        }.onFailure { releaseEffects(); MusicPlayback.error.value = "Audio effect unavailable: ${it.message}" }
    }
    private fun save() {
        if (!::player.isInitialized || !restored) return
        MusicRepository.update { it.copy(queue = (0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).mediaId }, queueIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            position = if (it.settings.rememberPosition) player.currentPosition.coerceAtLeast(0) else 0, repeat = player.repeatMode, shuffle = player.shuffleModeEnabled) }
    }
    private fun releaseEffects() { eq?.release(); eq = null; bass?.release(); bass = null }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MusicRepository.state.value.settings.background) player.pause()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }
    override fun onDestroy() {
        save(); scope.cancel(); handler.removeCallbacksAndMessages(null); releaseEffects(); MusicPlayback.engineSettings = null
        session?.release(); player.release(); super.onDestroy()
    }
}
internal fun LocalTrack.asMediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(artwork.takeIf { it.isNotEmpty() }?.let { android.net.Uri.fromFile(java.io.File(it)) }).build()).build()

object MusicPlayback {
    private var controller: MediaController? = null
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connecting = false
    private var lastEnqueue: Pair<List<String>, Boolean>? = null
    private var lastEnqueueTime = 0L
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state = _state.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    val downloadToPlay = MutableStateFlow<String?>(null)
    @Volatile internal var deadline = 0L
    internal var sleepMode = "Off"
    internal var engineSettings: (() -> Unit)? = null
    fun init(ctx: Context) { context = ctx.applicationContext }
    private fun command(action: (MediaController) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!MusicRepository.ready.value) { scope.launch { MusicRepository.ready.first { it }; command(action) }; return }
        controller?.let { runCatching { action(it) }.onFailure { e -> error.value = e.message }; return }
        pending.add(action)
        if (connecting) return
        connecting = true
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).setListener(object : MediaController.Listener {
            override fun onDisconnected(disconnected: MediaController) { if (controller == disconnected) controller = null }
        }).buildAsync()
        future.addListener({
            connecting = false
            runCatching { future.get() }.onSuccess { connected ->
                controller = connected
                pending.toList().also { pending.clear() }.forEach { operation -> runCatching { operation(connected) }.onFailure { error.value = it.message } }
            }.onFailure { pending.clear(); error.value = "Player connection failed: ${it.message}" }
        }, ContextCompat.getMainExecutor(context))
    }
    fun restore() = command { }
    fun play(ids: List<String>, start: Int = 0) = command { p ->
        val tracks = MusicRepository.state.value.tracks.associateBy { it.id }
        val media = ids.mapNotNull { id -> tracks[id]?.takeIf { it.available }?.asMediaItem() }
        require(media.isNotEmpty()) { "No accessible audio files. Refresh the library or import audio." }
        val selected = ids.take(start.coerceAtLeast(0)).count { id -> tracks[id]?.available == true }.coerceAtMost(media.lastIndex)
        lastPlaybackError = ""; error.value = null; p.setMediaItems(media, selected, 0); p.prepare(); p.play()
    }
    fun toggle() = command { if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition(); it.prepare(); it.play() } }
    fun pauseIfConnected() { controller?.pause() }
    fun stop() = command { it.stop(); it.clearMediaItems(); cancelTimer() }
    fun seek(position: Long) = command { it.seekTo(position.coerceAtLeast(0)) }
    fun next() = command { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = command { it.seekToPrevious() }
    fun repeat() = command { it.repeatMode = (it.repeatMode + 1) % 3 }
    fun shuffle() = command { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun enqueue(ids: List<String>, next: Boolean = false) {
        val key = ids.toList() to next
        val now = SystemClock.elapsedRealtime()
        if (key == lastEnqueue && now - lastEnqueueTime < 500) return
        lastEnqueue = key; lastEnqueueTime = now
        command { p ->
        val byId = MusicRepository.state.value.tracks.associateBy { it.id }
        val media = ids.mapNotNull { id -> byId[id]?.takeIf { it.available }?.asMediaItem() }
        p.addMediaItems(if (next) (p.currentMediaItemIndex + 1).coerceAtLeast(0) else p.mediaItemCount, media)
        }
    }
    fun remove(index: Int) = command { if (index > it.currentMediaItemIndex && index < it.mediaItemCount) it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = command { if (from > it.currentMediaItemIndex && to > it.currentMediaItemIndex && to < it.mediaItemCount) it.moveMediaItem(from, to) }
    fun clearUpcoming() = command { it.removeMediaItems((it.currentMediaItemIndex + 1).coerceAtLeast(0), it.mediaItemCount) }
    fun shuffleUpcoming() = command { p ->
        val start = (p.currentMediaItemIndex + 1).coerceAtLeast(0)
        val items = (start until p.mediaItemCount).map { p.getMediaItemAt(it) }.shuffled()
        p.removeMediaItems(start, p.mediaItemCount); p.addMediaItems(items)
    }
    fun timer(mode: String, minutes: Int = 0) { require(minutes in 0..1440); sleepMode = mode; deadline = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60000L else 0; restore() }
    fun cancelTimer() { sleepMode = "Off"; deadline = 0 }
    fun applySettings() { engineSettings?.invoke() }
    internal var lastPlaybackError = ""
    internal var lastPlayIntent = ""
    internal var diagnostics = ""
        private set
    private var timeline: Timeline? = null
    private var queueSnapshot: List<String> = emptyList()
    private var trackSource: List<LocalTrack>? = null
    private var trackIndex: Map<String, LocalTrack> = emptyMap()
    internal fun publish(p: Player, eq: Equalizer?, bass: BassBoost?) {
        diagnostics = "state=${p.playbackState}, ready=${p.playWhenReady}, suppression=${p.playbackSuppressionReason}, error=${p.playerError?.message}, count=${p.mediaItemCount}"
        val tracks = MusicRepository.state.value.tracks
        if (trackSource !== tracks) { trackSource = tracks; trackIndex = tracks.associateBy { it.id } }
        if (timeline !== p.currentTimeline) { timeline = p.currentTimeline; queueSnapshot = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId } }
        _state.value = PlaybackSnapshot(trackIndex[p.currentMediaItem?.mediaId] ?: p.currentMediaItem?.let { item ->
            LocalTrack(item.mediaId, item.localConfiguration?.uri.toString(), item.mediaMetadata.title?.toString() ?: "Current track", item.mediaMetadata.artist?.toString() ?: "Unknown artist")
        }, p.isPlaying, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0),
            queueSnapshot, p.currentMediaItemIndex.coerceAtLeast(0), p.shuffleModeEnabled, p.repeatMode,
            error.value, deadline, sleepMode, runCatching { eq?.let { effect -> (0 until effect.numberOfBands.toInt()).map { effect.getCenterFreq(it.toShort()) / 1000 } } }.getOrNull().orEmpty(),
            runCatching { eq?.bandLevelRange?.let { it[0].toInt() to it[1].toInt() } }.getOrNull() ?: (-1500 to 1500), eq != null, bass?.strengthSupported == true, runCatching { eq?.let { e -> (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) } } }.getOrNull().orEmpty(),
            runCatching { eq?.let { e -> (0 until e.numberOfBands.toInt()).map { e.getBandLevel(it.toShort()).toInt() } } }.getOrNull().orEmpty())
    }
}
