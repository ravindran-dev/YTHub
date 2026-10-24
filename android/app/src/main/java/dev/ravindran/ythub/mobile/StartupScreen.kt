package dev.ravindran.ythub.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput

/** Keep the logo present and centered through the platform-to-Compose startup handoff. */
@Composable
internal fun StartupScreen(ready: Boolean, onFinished: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(420)); revealed = true }
    LaunchedEffect(ready, revealed) { if (ready && revealed) finish() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().testTag("startup-screen").pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
    }) {
        val roomy = maxHeight > 420.dp
        Image(painterResource(R.drawable.ythub_logo), "YTHub", Modifier.align(Alignment.Center).size(92.dp).graphicsLayer {
            scaleX = .98f + .02f * reveal.value
            scaleY = scaleX
        })
        Column(Modifier.align(Alignment.TopCenter).offset(y = maxHeight / 2 + 68.dp).graphicsLayer { alpha = reveal.value },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("YTHub", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineLarge)
            if (roomy) Text("Your music. Your space.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            CircularProgressIndicator(Modifier.padding(top = 24.dp).size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
