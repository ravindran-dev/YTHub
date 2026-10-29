package dev.ravindran.ythub.mobile

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One interaction source per glass surface lets a capsule flex as one continuous object.
internal data class GlassScene(val layer: GraphicsLayer, val origin: Offset)
internal val LocalGlassScene = staticCompositionLocalOf<GlassScene?> { null }
private val LocalGlassInteraction = staticCompositionLocalOf<MutableInteractionSource?> { null }

/** Bounded feedback without the rectangular Material ripple. Haptics respect system settings. */
@Composable
internal fun Modifier.glassTouch(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val shared = LocalGlassInteraction.current
    val interaction = shared ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pressed) { if (shared == null && pressed && enabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val feedback by animateFloatAsState(if (shared == null && pressed) 1f else 0f, spring(dampingRatio = .8f, stiffness = 450f), label = "Row press")
    val feedbackColor = MaterialTheme.colorScheme.onSurface
    return graphicsLayer { scaleX = 1f - .015f * feedback; scaleY = 1f - .025f * feedback }
        .drawBehind { drawRect(feedbackColor.copy(alpha = .045f * feedback.coerceIn(0f, 1f))) }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** Samples the shared, already frosted ambient image without filtering each moving panel again. */
@Composable
internal fun GlassBackdrop(layer: GraphicsLayer, origin: Offset, position: State<Offset>, magnification: Float = 1.035f) {
    val backing = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(backing)
        if (layer.size.width > 0 && layer.size.height > 0) {
            // Only the ambient image is sampled. Scrolling rows stay on their own rendering path.
            with(drawContext.canvas) {
                save()
                val centre = Offset(size.width / 2f, size.height / 2f)
                translate(centre.x, centre.y)
                scale(magnification, magnification)
                translate(-centre.x, -centre.y)
                translate(origin.x - position.value.x, origin.y - position.value.y)
                drawLayer(layer)
                restore()
            }
        }
    })
}

/** One frosted image layer with a restrained reflective rim; labels stay sharp. */
@Composable
internal fun GlassControlSurface(modifier: Modifier = Modifier, dark: Boolean = MaterialTheme.colorScheme.background.luminance() < .5f, backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50), screenPosition: Boolean = false, flexible: Boolean = true, tintAlpha: Float? = null, onPressedChange: (Boolean) -> Unit = {}, dockBackdrop: DockGlassState? = null, magnification: Float = 1.035f, content: @Composable BoxScope.() -> Unit) {
    val ambientBackdrop = LocalAtmosphereLayer.current
    val origin = remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pressed) { onPressedChange(pressed); if (pressed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val compression by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = .72f, stiffness = 450f), label = "Glass compression")
    val highlight by animateFloatAsState(if (pressed) .10f else if (hovered) .04f else 0f, spring(stiffness = 600f), label = "Glass highlight")
    val highlightColor = MaterialTheme.colorScheme.onSurface
    val rimColors = if (dark) listOf(Color.White.copy(alpha = .40f), Color.White.copy(alpha = .06f), Color.White.copy(alpha = .18f))
        else listOf(Color.White.copy(alpha = .95f), Color(0xFF7A8090).copy(alpha = .20f), Color.White.copy(alpha = .72f))
    Box(modifier.graphicsLayer { scaleX = 1f - .025f * compression; scaleY = 1f - .055f * compression }.shadow(4.dp, shape, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .15f)).clip(shape).onGloballyPositioned { origin.value = if (screenPosition) it.positionOnScreen() else it.positionInRoot() }
        .border(.65.dp, Brush.verticalGradient(rimColors), shape)) {
        Box(Modifier.matchParentSize()) {
            if (dockBackdrop != null) DockGlassBackdrop(dockBackdrop, origin)
            else if (backdrop != null) GlassBackdrop(backdrop, contentOrigin, origin, magnification)
            else if (ambientBackdrop != null) GlassBackdrop(ambientBackdrop, Offset.Zero, origin, magnification)
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF28282D).copy(alpha = tintAlpha ?: .30f) else Color.White.copy(alpha = tintAlpha ?: .64f)))
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) .14f else .30f), .48f to Color.White.copy(alpha = .015f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().drawBehind { drawRect(highlightColor.copy(alpha = highlight)) })
        CompositionLocalProvider(LocalGlassInteraction provides if (flexible) interaction else null) { content() }
    }
}

@Composable
internal fun FloatingPlaybackDock(onHome: () -> Unit, onPlayer: () -> Unit, onLibrary: () -> Unit, onSearch: () -> Unit, dark: Boolean, dockBackdrop: DockGlassState) {
    val dockState = remember { MusicPlayback.state.map { it.track to it.playing }.distinctUntilChanged() }
    val player by dockState.collectAsStateWithLifecycle(initialValue = remember { MusicPlayback.state.value.let { it.track to it.playing } })
    val track = player.first
    val dockHeight = maxOf(56.dp, with(LocalDensity.current) { 30.sp.toDp() } + 20.dp)
    val tint = if (dark) .10f else .28f
    Row(Modifier.fillMaxWidth().onGloballyPositioned { dockBackdrop.bounds = it.boundsInRoot() }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).testTag("floating-playback-dock"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassControlSurface(Modifier.size(54.dp), dark, dockBackdrop = dockBackdrop, tintAlpha = tint) {
            GlassIconButton(Icons.Rounded.Home, "Home", onHome, Modifier.fillMaxSize().testTag("nav-Home"))
        }
        GlassControlSurface(Modifier.weight(1f).height(dockHeight).testTag("dock-glass-player"), dark, dockBackdrop = dockBackdrop, tintAlpha = tint) {
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).glassTouch(onClick = if (track == null) onLibrary else onPlayer).testTag("music-mini-player"), verticalAlignment = Alignment.CenterVertically) {
                    TrackArtwork(track, Modifier.size(40.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(track?.title ?: "Nothing playing", fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track?.artist ?: "Open Library", fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                GlassIconButton(if (track == null) Icons.Rounded.LibraryMusic else if (player.second) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (track == null) "Open Library" else if (player.second) "Pause" else "Play",
                    if (track == null) onLibrary else MusicPlayback::toggle)

            }
        }
        GlassControlSurface(Modifier.size(54.dp), dark, dockBackdrop = dockBackdrop, tintAlpha = tint) {
            GlassIconButton(Icons.Rounded.Search, "Search local music", onSearch, Modifier.fillMaxSize().testTag("nav-Search"))
        }
    }
}

@Composable
internal fun ContextControls(onBack: () -> Unit, onAdd: () -> Unit, onMore: () -> Unit, backLabel: String = "Go back", addLabel: String = "Add to playlist", backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack) {
    val scene = LocalGlassScene.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassControlSurface(Modifier.size(48.dp), backdrop = scene?.layer, contentOrigin = scene?.origin ?: Offset.Zero) {
            GlassIconButton(backIcon, backLabel, onBack)
        }
        Spacer(Modifier.weight(1f))
        PairedGlassActions(onAdd, onMore, addLabel = addLabel)

    }
}

@Composable
internal fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: Dp = 21.dp) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(50)).glassTouch(onClick, enabled)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(glyph), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .3f))
    }
}

/** Anchored, focusable popover: outside touch and Android Back dismiss it. */
@Composable
internal fun GlassActionPopover(title: String, onDismiss: () -> Unit, backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero,
    content: @Composable ColumnScope.() -> Unit) {
    val scene = LocalGlassScene.current
    GlassPopover(onDismiss) {
        GlassMenuBlock(backdrop ?: scene?.layer, if (backdrop == null) scene?.origin ?: contentOrigin else contentOrigin) {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp.dp - 170.dp).coerceAtLeast(200.dp)).verticalScroll(rememberScrollState())) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            content()
            }
        }
    }
}

@Composable
internal fun GlassPopover(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val available = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtLeast(180.dp)
    val host = LocalGlassPopoverHost.current
    val view = LocalView.current
    val panel: @Composable () -> Unit = {
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(Unit) { reveal.animateTo(1f, spring(dampingRatio = .82f, stiffness = 520f)) }
        Column(Modifier.widthIn(max = available).width(252.dp).padding(4.dp).graphicsLayer {
            alpha = reveal.value.coerceIn(0f, 1f)
            scaleX = .88f + .12f * reveal.value
            scaleY = .80f + .20f * reveal.value
            translationY = -8.dp.toPx() * (1f - reveal.value)
            transformOrigin = TransformOrigin(1f, 0f)
        }.testTag("glass-popover"), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
    if (host != null && host.view === view) HostedGlassPopover(host, onDismiss, panel)
    else Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, with(density) { 58.dp.roundToPx() }), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = true), content = panel)
}

@Composable
internal fun GlassMenuBlock(backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero, content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassPanel(Modifier.fillMaxWidth(), backdrop = backdrop, contentOrigin = contentOrigin, radius = 28.dp) {
        Column(Modifier.fillMaxWidth().padding(6.dp), content = content)
    }
}

@Composable
internal fun PairedGlassActions(onAdd: () -> Unit, onMore: () -> Unit, addLabel: String = "Phone music", moreLabel: String = "More options",
    backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero) {
    val scene = LocalGlassScene.current
    GlassControlSurface(Modifier.height(48.dp).testTag("paired-glass-actions"),
        backdrop = backdrop ?: scene?.layer,
        contentOrigin = if (backdrop == null) scene?.origin ?: contentOrigin else contentOrigin) {
        // Two accessible touch targets, one continuous material and one shared press response.
        Row(Modifier.padding(horizontal = 3.dp)) {
            GlassIconButton(Icons.Rounded.Add, addLabel, onAdd, glyph = 22.dp)
            Box(Modifier.width(.7.dp).height(22.dp).align(Alignment.CenterVertically).background(Color.White.copy(alpha = .12f)))
            GlassIconButton(Icons.Rounded.MoreHoriz, moreLabel, onMore, glyph = 22.dp)
        }
    }
}
