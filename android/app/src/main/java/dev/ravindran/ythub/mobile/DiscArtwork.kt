package dev.ravindran.ythub.mobile

import android.animation.ValueAnimator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

/** A real-artwork sleeve and disc; playback controls rotation, never a pre-rendered GIF. */
@Composable
internal fun DiscArtwork(track: LocalTrack, queueIndex: Int, playing: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(track to queueIndex, contentKey = { it.first.id to it.second }, modifier = modifier,
        transitionSpec = {
            val direction = if (targetState.second >= initialState.second) 1 else -1
            (fadeIn(tween(280)) + slideInHorizontally(spring(dampingRatio = .9f, stiffness = 300f)) { direction * it / 3 }) togetherWith
                (fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { -direction * it / 3 })
        }, label = "Disc changeover") { (art, _) ->
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val sleeve = minOf(maxHeight, maxWidth * .82f)
            val disc = sleeve * .91f
            var rotation by remember(art.id) { mutableFloatStateOf(0f) }
            val lifecycle = LocalLifecycleOwner.current
            LaunchedEffect(playing, art.id, lifecycle) {
                if (playing && ValueAnimator.areAnimatorsEnabled()) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    var previousFrame = 0L
                    while (isActive && ValueAnimator.areAnimatorsEnabled()) {
                        withInfiniteAnimationFrameNanos { frame ->
                            if (previousFrame != 0L) rotation = (rotation + (frame - previousFrame) / 1_000_000_000f * (360f / 14f)) % 360f
                            previousFrame = frame
                        }
                    }
                }
            }
            TrackArtwork(art, Modifier.align(Alignment.CenterStart).size(sleeve).shadow(14.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)))
            Box(Modifier.align(Alignment.CenterEnd).size(disc).shadow(10.dp, CircleShape).clip(CircleShape)
                .graphicsLayer { rotationZ = rotation }.testTag("rotating-disc")
                .semantics { stateDescription = if (playing) "Playing" else "Paused" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF54585C), Color(0xFF17191B), Color(0xFF3B4045), Color(0xFF111315))))
                    repeat(12) { ring -> drawCircle(Color.White.copy(alpha = .045f), size.minDimension * (.29f + ring * .016f), style = Stroke(.65.dp.toPx())) }
                    drawCircle(Color.White.copy(alpha = .28f), size.minDimension * .485f, style = Stroke(.8.dp.toPx()))
                    drawArc(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .20f), Color.Transparent, Color(0xFF94BFC7).copy(alpha = .16f), Color.Transparent)),
                        0f, 360f, false, Offset.Zero, size, style = Stroke(size.minDimension * .24f))
                }
                TrackArtwork(art, Modifier.fillMaxSize(.49f).clip(CircleShape))
                Box(Modifier.size(13.dp).clip(CircleShape).background(Color(0xFF111316)))
            }

        }
    }
}
