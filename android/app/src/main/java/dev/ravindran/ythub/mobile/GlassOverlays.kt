@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.ravindran.ythub.mobile

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider

/** Rounded translucent panels share the menu's material without scaling an entire form on a row press. */
@Composable
internal fun GlassSheet(onDismissRequest: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content: @Composable ColumnScope.() -> Unit) {
    val scene = LocalGlassScene.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
        containerColor = Color.Transparent, dragHandle = null, scrimColor = Color.Black.copy(alpha = .22f)) {
        GlassWindowBlur()
        GlassControlSurface(Modifier.fillMaxWidth().padding(horizontal = 8.dp), backdrop = scene?.layer, contentOrigin = scene?.origin ?: androidx.compose.ui.geometry.Offset.Zero,
            shape = RoundedCornerShape(28.dp), screenPosition = true, flexible = false, tintAlpha = if (dark) .78f else .94f) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).size(34.dp, 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .25f)))
                content()
            }
        }
    }
}

@Composable
internal fun GlassConfirmDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null) {
    val scene = LocalGlassScene.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Dialog(onDismissRequest = onDismissRequest) {
        GlassWindowBlur()
        GlassControlSurface(Modifier.fillMaxWidth(), backdrop = scene?.layer, contentOrigin = scene?.origin ?: androidx.compose.ui.geometry.Offset.Zero,
            shape = RoundedCornerShape(26.dp), screenPosition = true, flexible = false, tintAlpha = if (dark) .84f else .96f) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .78f).dp).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (icon != null) Box(Modifier.align(Alignment.CenterHorizontally)) { icon() }
                if (title != null) ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                if (text != null) ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton?.invoke(); confirmButton() }
            }
        }
    }
}

@Composable
internal fun GlassMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).glassTouch(onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
    }
}

@Composable
private fun GlassWindowBlur() {
    val view = LocalView.current
    DisposableEffect(view) {
        var parent: android.view.ViewParent? = view.parent
        var provider: DialogWindowProvider? = null
        while (parent != null && provider == null) {
            provider = parent as? DialogWindowProvider
            parent = parent.parent
        }
        val window = provider?.window
        if (Build.VERSION.SDK_INT >= 31 && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 28 }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 31) window?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }
}
