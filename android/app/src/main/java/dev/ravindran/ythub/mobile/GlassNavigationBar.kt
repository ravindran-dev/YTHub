package dev.ravindran.ythub.mobile

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Draws the screen behind the glass; labels and touch targets remain sharp. */
@Composable
internal fun GlassBackdrop(layer: GraphicsLayer, origin: Offset, position: Offset, blur: Float, magnification: Float = 1f) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        if (Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
    }.drawBehind {
        if (layer.size.width > 0 && layer.size.height > 0) {
            // Zoom around the glass centre, rather than around the source screen origin.
            val centre = Offset(size.width / 2, size.height / 2)
            with(drawContext.canvas) {
                save()
                translate(centre.x, centre.y)
                scale(magnification, magnification)
                translate(-centre.x, -centre.y)
                translate(origin.x - position.x, origin.y - position.y)
                drawLayer(layer)
                restore()
            }
        }
    })
}

@Composable
internal fun GlassNavigationBar(selected: String, onSelect: (String) -> Unit, dark: Boolean, backdrop: GraphicsLayer, contentOrigin: Offset) {
    val entries = listOf("Home", "Downloads", "Library", "Settings")
    val selectedIndex = entries.indexOf(selected).coerceAtLeast(0)
    val position by animateFloatAsState(selectedIndex.toFloat(), spring(dampingRatio = .9f, stiffness = 250f), label = "Moving glass selection")
    val travel = abs(position - selectedIndex).coerceIn(0f, 1f)
    val stretch by animateFloatAsState(1f + .14f * travel, spring(dampingRatio = .9f, stiffness = 400f), label = "Glass stretch and settle")
    val shape = RoundedCornerShape(40.dp)
    val density = LocalDensity.current
    val barHeight = maxOf(64.dp, 36.dp + with(density) { 14.sp.toDp() } * 2)
    var glassOrigin by remember { mutableStateOf(Offset.Zero) }
    val rim = if (dark) Color.White.copy(alpha = .18f) else Color.White.copy(alpha = .9f)
    val tint = if (dark) Color(0xFF11151D).copy(alpha = .48f) else Color(0xFFF8F8FC).copy(alpha = .68f)

    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(barHeight)
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = .15f), spotColor = Color.Black.copy(alpha = .22f))
            .clip(shape).onGloballyPositioned { glassOrigin = it.positionInRoot() }
            .border(1.dp, Brush.verticalGradient(listOf(rim, rim.copy(alpha = .06f), rim.copy(alpha = .18f))), shape)
            .testTag("floating-glass-navigation")) {
            GlassBackdrop(backdrop, contentOrigin, glassOrigin, 22f)
            Box(Modifier.fillMaxSize().background(tint).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .09f else .4f), Color.Transparent, Color.Black.copy(alpha = .05f)))))
            val available = maxWidth - 8.dp
            val textMeasurer = rememberTextMeasurer()
            val baseLabelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
            val sizing = remember(available, density, baseLabelStyle, textMeasurer) {
                var font = 11f
                fun widths() = entries.map { label -> maxOf(48.dp, with(density) { textMeasurer.measure(label, baseLabelStyle.copy(fontSize = font.sp), maxLines = 1).size.width.toDp() } + 4.dp) }
                var required = widths()
                while (required.map { it.value }.sum() > available.value && font > 10.01f) { font -= .1f; required = widths() }
                val equal = available / entries.size
                val total = required.map { it.value }.sum().dp
                val slots = if (required.all { it <= equal } || total > available) List(entries.size) { equal }
                    else required.map { it + (available - total) / entries.size }
                font to slots
            }
            val labelStyle = baseLabelStyle.copy(fontSize = sizing.first.sp)
            val slots = sizing.second
            val currentPosition = position.coerceIn(0f, entries.lastIndex.toFloat())
            val leftIndex = currentPosition.toInt()
            val fraction = currentPosition - leftIndex
            val nextIndex = (leftIndex + 1).coerceAtMost(entries.lastIndex)
            val selectionLeft = slots.take(leftIndex).fold(0.dp) { total, width -> total + width } + slots[leftIndex] * fraction
            val selectionWidth = slots[leftIndex] + (slots[nextIndex] - slots[leftIndex]) * fraction
            val lensShape = RoundedCornerShape(28.dp)
            val lensWidth = selectionWidth * stretch
            Box(Modifier.offset(x = 4.dp + selectionLeft - (lensWidth - selectionWidth) / 2, y = 6.dp).width(lensWidth).height(barHeight - 12.dp)
                .clip(lensShape)
                .border(.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .32f else .85f), Color.White.copy(alpha = .03f), Color.White.copy(alpha = .12f))), lensShape)) {
                // The base already supplies real blur; a second render-effect pass here duplicates GPU work.
                Box(Modifier.fillMaxSize().background(if (dark) Color.White.copy(alpha = .075f) else Color(0xFFE2E3EB).copy(alpha = .45f))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .09f else .3f), Color.Transparent))))
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 4.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                entries.forEachIndexed { index, label ->
                    val active = label == selected
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) .92f else if (active) 1.1f else 1f, spring(dampingRatio = .82f, stiffness = 380f), label = "$label press and release")
                    val lift by animateFloatAsState(if (active && !pressed) -1f else 0f, spring(dampingRatio = .9f, stiffness = 250f), label = "$label lift")
                    val color by animateColorAsState(if (active) Color(0xFFFF173D) else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), tween(220), label = "$label glass tint")
                    Box(Modifier.width(slots[index]).fillMaxHeight().clip(lensShape)
                        .selectable(selected = active, interactionSource = interaction, indication = null, onClick = { onSelect(label) }, role = Role.Tab)
                        .testTag("nav-$label"), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp).graphicsLayer { translationY = lift.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            NavigationGlyph(label, color, Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                            Spacer(Modifier.height(2.dp))
                            Text(label, style = labelStyle, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, color = color, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** A consistent set of rounded line glyphs, drawn on a 24-point grid. */
@Composable private fun NavigationGlyph(name: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.width / 24f
        val stroke = Stroke(width = unit * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
        when (name) {
            "Home" -> {
                path { moveTo(3 * unit, 10.5f * unit); lineTo(11 * unit, 3.8f * unit); quadraticTo(12 * unit, 3 * unit, 13 * unit, 3.8f * unit); lineTo(21 * unit, 10.5f * unit) }
                path { moveTo(5 * unit, 9 * unit); lineTo(5 * unit, 19 * unit); quadraticTo(5 * unit, 21 * unit, 7 * unit, 21 * unit); lineTo(9.5f * unit, 21 * unit); lineTo(9.5f * unit, 15 * unit); quadraticTo(9.5f * unit, 14 * unit, 10.5f * unit, 14 * unit); lineTo(13.5f * unit, 14 * unit); quadraticTo(14.5f * unit, 14 * unit, 14.5f * unit, 15 * unit); lineTo(14.5f * unit, 21 * unit); lineTo(17 * unit, 21 * unit); quadraticTo(19 * unit, 21 * unit, 19 * unit, 19 * unit); lineTo(19 * unit, 9 * unit) }
            }
            "Downloads" -> {
                line(12f, 3f, 12f, 14f); line(8f, 10f, 12f, 14f); line(12f, 14f, 16f, 10f)
                path { moveTo(4 * unit, 15 * unit); lineTo(4 * unit, 19 * unit); quadraticTo(4 * unit, 21 * unit, 6 * unit, 21 * unit); lineTo(18 * unit, 21 * unit); quadraticTo(20 * unit, 21 * unit, 20 * unit, 19 * unit); lineTo(20 * unit, 15 * unit) }
            }
            "Player" -> {
                drawCircle(color.copy(alpha = .15f), 11 * unit, point(12f, 12f))
                path { moveTo(9 * unit, 6 * unit); lineTo(18 * unit, 12 * unit); lineTo(9 * unit, 18 * unit); close() }
            }
            "Library" -> {
                line(8f, 2f, 16f, 2f)
                line(5f, 5f, 19f, 5f)
                drawRoundRect(color, point(3f, 8f), Size(18 * unit, 13 * unit), CornerRadius(2 * unit), style = stroke)
                path { moveTo(11 * unit, 17 * unit); lineTo(11 * unit, 12 * unit); lineTo(17 * unit, 11 * unit); lineTo(17 * unit, 16 * unit) }
                drawOval(color, point(7.5f, 16f), Size(3.5f * unit, 2.8f * unit))
                drawOval(color, point(13.5f, 15f), Size(3.5f * unit, 2.8f * unit))
            }
            "History" -> {
                drawArc(color, 210f, 310f, false, point(3f, 3f), Size(18 * unit, 18 * unit), style = stroke)
                path { moveTo(3 * unit, 3.5f * unit); lineTo(3 * unit, 8.5f * unit); lineTo(8 * unit, 8.5f * unit) }
                line(12f, 7f, 12f, 12f); line(12f, 12f, 15.5f, 14f)
            }
            "Settings" -> {
                val gear = Path()
                repeat(64) { index ->
                    val angle = (index * Math.PI / 32 - Math.PI / 2).toFloat()
                    val radius = if (index % 8 in 2..5) 9.2f else 7.5f
                    val x = (12f + cos(angle) * radius) * unit
                    val y = (12f + sin(angle) * radius) * unit
                    if (index == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
                gear.close(); drawPath(gear, color, style = stroke)
                drawCircle(color, 3.1f * unit, point(12f, 12f), style = stroke)
            }
        }
    }
}
