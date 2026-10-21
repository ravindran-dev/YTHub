package dev.ravindran.ythub.mobile

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Finite UI transitions use Compose's system duration scale, including disabled animations. */
internal object MobileMotion {
    const val quick = 140
    const val standard = 220
    const val navigation = 260
    fun navigation(from: String, to: String): ContentTransform {
        val tabs = listOf("Home", "Downloads", "Library", "Settings")
        val enter = fadeIn(tween(standard))
        val exit = fadeOut(tween(quick))
        return when {
            to == "Player" -> (enter + slideInVertically(tween(navigation, easing = FastOutSlowInEasing)) { it / 14 } + scaleIn(tween(navigation), initialScale = .98f)) togetherWith exit
            from == "Player" -> enter togetherWith (exit + slideOutVertically(tween(standard)) { it / 18 })
            from in tabs && to in tabs -> {
                val direction = if (tabs.indexOf(to) > tabs.indexOf(from)) 1 else -1
                (enter + slideInHorizontally(tween(navigation, easing = FastOutSlowInEasing)) { direction * it / 16 }) togetherWith
                    (exit + slideOutHorizontally(tween(standard)) { -direction * it / 24 })
            }
            else -> (enter + slideInHorizontally(tween(standard)) { it / 20 }) togetherWith exit
        }
    }
}

@Composable internal fun DownloadMotionProgress(item: DownloadItem, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState((item.progress / 100f).coerceIn(0f, 1f), tween(320, easing = LinearOutSlowInEasing), label = "Download byte progress")
    val processing = item.state in listOf("Processing", "Exporting", "Zipping")
    if (processing) LinearProgressIndicator(modifier = modifier, color = MobileUi.accent)
    else LinearProgressIndicator(progress = { progress }, modifier = modifier, color = MobileUi.accent)
}

@Composable internal fun DownloadMotionStatus(state: String, color: Color) {
    AnimatedContent(state, transitionSpec = {
        (fadeIn(tween(MobileMotion.standard)) + scaleIn(tween(MobileMotion.standard), initialScale = .85f)) togetherWith fadeOut(tween(MobileMotion.quick))
    }, label = "Download stage") { status ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val icon = when (status) {
                "Completed" -> Icons.Rounded.CheckCircle
                "Failed", "Cancelled" -> Icons.Rounded.ErrorOutline
                "Paused", "Interrupted" -> Icons.Rounded.PauseCircleOutline
                "Processing", "Exporting", "Zipping" -> Icons.Rounded.AutoFixHigh
                else -> Icons.Rounded.Downloading
            }
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Text(status, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Stable occurrence keys allow repeated songs without tying row identity to its position. */
internal fun motionTrackKeys(ids: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return ids.map { id ->
        val occurrence = occurrences[id] ?: 0
        occurrences[id] = occurrence + 1
        "$id:$occurrence"
    }
}
