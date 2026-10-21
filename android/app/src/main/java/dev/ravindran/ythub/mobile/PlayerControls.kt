package dev.ravindran.ythub.mobile

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Animates the visual glyph; the accessible hit target never shrinks. */
@Composable internal fun PlayerControl(icon: ImageVector, description: String, onClick: () -> Unit, active: Boolean = false, enabled: Boolean = true, size: Dp = 48.dp, glyph: Dp = 24.dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pulse = remember { Animatable(1f) }
    var previousIcon by remember { mutableStateOf(icon) }
    LaunchedEffect(icon) {
        if (previousIcon != icon) {
            previousIcon = icon
            pulse.animateTo(1.12f, tween(80))
            pulse.animateTo(1f, spring(dampingRatio = .7f, stiffness = 500f))
        }
    }
    val scale by animateFloatAsState(if (pressed) .88f else 1f, spring(dampingRatio = .8f, stiffness = 450f), label = "Player control press")
    val color by animateColorAsState(if (!enabled) Color.White.copy(alpha = .3f) else if (active) MobileUi.accent else Color.White, tween(140), label = "Player control state")
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = Modifier.size(size)) {
        Crossfade(icon, animationSpec = tween(140), label = "Player glyph") { vector ->
            Icon(vector, description, Modifier.size(glyph).graphicsLayer { scaleX = scale * pulse.value; scaleY = scale * pulse.value }, tint = color)
        }
    }
}

/** System media volume, shared with the hardware buttons and the active Android audio route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerVolume() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var maximum by remember { mutableIntStateOf(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }
    var minimum by remember { mutableIntStateOf(if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0) }
    var fixed by remember { mutableStateOf(audio.isVolumeFixed) }
    LaunchedEffect(lifecycle, audio) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
                fixed = audio.isVolumeFixed
                delay(500)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.VolumeDown, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = .6f))
        GlassPlayerSlider(value = volume.toFloat(), onValueChange = { requested ->
            val next = requested.roundToInt().coerceIn(minimum, maximum.coerceAtLeast(minimum))
            if (next != volume) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0); volume = next }
                .onFailure { MusicPlayback.error.value = "Android could not change media volume: ${it.message}" }
        }, valueRange = minimum.toFloat()..maximum.coerceAtLeast(minimum + 1).toFloat(), enabled = !fixed && maximum > minimum,
            description = "Media volume", modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.VolumeUp, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = .6f))
    }
    if (fixed) Text("Volume is controlled by the connected device", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .65f))
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            .onFailure { MusicPlayback.error.value = "Android audio settings are unavailable: ${it.message}" }
    }) {
        Icon(Icons.Rounded.DevicesOther, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = .75f))
        Text("Audio output", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .8f))
    }
}

/** A single centered translucent capsule, with native Slider gestures and accessibility semantics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GlassPlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val engaged = enabled && (dragging || pressed || focused)
    val thickness by animateFloatAsState(if (engaged) 8f else 4f, spring(dampingRatio = 1f, stiffness = 500f), label = "Slider thickness")
    val opacity by animateFloatAsState(if (!enabled) .25f else if (engaged) .9f else .6f, tween(160), label = "Slider opacity")
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
    val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
    val progress by animateFloatAsState(fraction, if (engaged) snap() else tween(220, easing = LinearOutSlowInEasing), label = "Slider progress")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(value = value.coerceIn(valueRange), onValueChange = onValueChange, valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished, enabled = enabled, interactionSource = interaction,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        thumb = { Box(Modifier.size(0.dp)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val height = thickness.dp.toPx()
                val top = (size.height - height) / 2f
                val radius = CornerRadius(height / 2f)
                drawRoundRect(Color.White.copy(alpha = if (enabled) .12f else .06f), Offset(0f, top), Size(size.width, height), radius)
                val width = size.width * progress
                if (width > 0f) drawRoundRect(Color.White.copy(alpha = opacity), Offset(if (rtl) size.width - width else 0f, top), Size(width, height), radius)
            }
        })
}
