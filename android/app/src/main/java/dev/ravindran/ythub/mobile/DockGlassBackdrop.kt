package dev.ravindran.ythub.mobile

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** A single cropped, half-resolution underlay shared by all three dock surfaces. */
internal class DockGlassState(val layer: GraphicsLayer) {
    var bounds by mutableStateOf(Rect.Zero)
    var contentOrigin by mutableStateOf(Offset.Zero)
    var scrolling by mutableStateOf(false)
}

@Composable
internal fun rememberDockGlassState(): DockGlassState {
    val layer = rememberGraphicsLayer()
    return remember(layer) {
        if (Build.VERSION.SDK_INT >= 31) layer.renderEffect = BlurEffect(12f, 12f, TileMode.Clamp)
        DockGlassState(layer)
    }
}

/** Capture only the strip behind the dock. The dock itself is outside this draw subtree. */
@Composable
internal fun Modifier.captureDockGlass(state: DockGlassState, atmosphere: GraphicsLayer, enabled: Boolean = true): Modifier {
    val base = MaterialTheme.colorScheme.background
    val contentLayer = rememberGraphicsLayer()
    val captureMode = remember { object { var ambientOnly = false } }
    val scope = rememberCoroutineScope()
    val scrollObserver = remember(state, scope) {
        object : NestedScrollConnection {
            var idleJob: Job? = null
            var lastScrollAt = 0L
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available == Offset.Zero) return Offset.Zero
                lastScrollAt = SystemClock.uptimeMillis()
                state.scrolling = true
                if (idleJob?.isActive != true) idleJob = scope.launch {
                    // Keep a single idle watcher for the whole drag/fling, not one per frame.
                    do { delay(120) } while (SystemClock.uptimeMillis() - lastScrollAt < 120)
                    state.scrolling = false
                }
                return Offset.Zero
            }
        }
    }
    DisposableEffect(state) { onDispose { state.scrolling = false } }
    return nestedScroll(scrollObserver).onGloballyPositioned { state.contentOrigin = it.positionInRoot() }.drawWithContent {
        if (state.scrolling || !enabled) {
            // Keep the last frosted strip during motion. Scrolling content goes directly
            // to the screen without a full-screen intermediate layer or live blur pass.
            if (enabled && !captureMode.ambientOnly && state.bounds.width > 0f && state.bounds.height > 0f) {
                val crop = state.bounds.inflate(24.dp.toPx())
                // Drop references to moving list layers, so the cached blur cannot be
                // invalidated indirectly by their RenderNodes during the gesture.
                state.layer.record(size = IntSize(ceil(crop.width / 2f).toInt(), ceil(crop.height / 2f).toInt())) {
                    drawRect(base)
                    scale(.5f, .5f, Offset.Zero) {
                        translate(-crop.left, -crop.top) { drawLayer(atmosphere) }
                    }
                }
                captureMode.ambientOnly = true
            }
            drawContent()
            return@drawWithContent
        }
        captureMode.ambientOnly = false
        // Record the content once; both the screen and frosted crop replay this layer.
        contentLayer.record { this@drawWithContent.drawContent() }
        val bounds = state.bounds
        if (enabled && bounds.width > 0f && bounds.height > 0f) {
            val crop = bounds.inflate(24.dp.toPx())
            state.layer.record(size = IntSize(ceil(crop.width / 2f).toInt(), ceil(crop.height / 2f).toInt())) {
                drawRect(base)
                scale(.5f, .5f, Offset.Zero) {
                    translate(-crop.left, -crop.top) { drawLayer(atmosphere) }
                    translate(state.contentOrigin.x - crop.left, state.contentOrigin.y - crop.top) {
                        drawLayer(contentLayer)
                    }
                }
            }
        }
        drawLayer(contentLayer)
    }
}

@Composable
internal fun DockGlassBackdrop(state: DockGlassState, position: State<Offset>) {
    val base = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(base)
        if (state.layer.size.width > 0 && state.layer.size.height > 0) {
            val crop = state.bounds.inflate(24.dp.toPx())
            translate(crop.left - position.value.x, crop.top - position.value.y) {
                scale(2f, 2f, Offset.Zero) { drawLayer(state.layer) }
            }
        }
    })
}
