package dev.ravindran.ythub.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object MobileUi {
    val background = Color(0xFF090A0D)
    val surface = Color(0xFF111319)
    val elevated = Color(0xFF191C24)
    val accent = Color(0xFFFF1838)
    val page = 16.dp
    val card = RoundedCornerShape(16.dp)
}
internal val PlayerCrimson = MobileUi.accent
internal fun musicTypography(): Typography {
    val family = FontFamily(Font(R.font.inter), Font(R.font.inter, FontWeight.Bold), Font(R.font.inter, FontWeight.Medium), Font(R.font.inter, FontWeight.SemiBold))
    val t = Typography()
    return t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = t.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        headlineMedium = t.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = t.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
        titleMedium = t.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
        titleSmall = t.titleSmall.copy(fontFamily = family), bodyLarge = t.bodyLarge.copy(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = family), bodySmall = t.bodySmall.copy(fontFamily = family),
        labelLarge = t.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontFamily = family), labelSmall = t.labelSmall.copy(fontFamily = family)
    )
}
@Composable internal fun MusicSectionTitle(title: String, action: String = "See all", onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action); Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp)) }
    }
}
@Composable internal fun MusicArtworkCard(track: LocalTrack, subtitle: String = track.artist, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = track.available, shape = MobileUi.card, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)), modifier = modifier) {
        Column(Modifier.padding(8.dp)) {
            Box {
                TrackArtwork(track, Modifier.fillMaxWidth().aspectRatio(1f))
                if (onMore != null) IconButton(onClick = onMore, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.MoreHoriz, "Options for ${track.title}", tint = Color.White) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            Text(if (track.available) subtitle else "File unavailable", Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun MusicShelf(title: String, tracks: List<LocalTrack>, empty: String, onAll: () -> Unit, onTrack: (LocalTrack) -> Unit) {
    MusicSectionTitle(title, onAction = onAll)
    if (tracks.isEmpty()) Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Text(empty, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tracks.take(8), key = { it.id }) { track -> MusicArtworkCard(track, modifier = Modifier.width(132.dp)) { onTrack(track) } }
    }
}
@Composable internal fun PlaylistArtworkRow(playlist: LocalPlaylist, tracks: List<LocalTrack>, onClick: () -> Unit) {
    val ordered = remember(playlist.tracks, tracks) { val byId = tracks.associateBy { it.id }; playlist.tracks.mapNotNull { byId[it] } }
    Surface(onClick = onClick, shape = MobileUi.card, border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(ordered.firstOrNull(), Modifier.size(52.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("${playlist.tracks.size} tracks · ${durationLabel(ordered.sumOf { it.durationMs } / 1000)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(if (playlist.downloadId != null) "Downloaded playlist" else "Local playlist", style = MaterialTheme.typography.labelSmall, color = PlayerCrimson, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Default.ChevronRight, "Open playlist")
        }
    }
}
@Composable internal fun MusicSettingsSection(title: String, summary: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(MobileMotion.standard), label = "Settings disclosure")
    Surface(shape = MobileUi.card, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))) {
        Column {
            Row(Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.clickable(role = Role.Button) { expanded = !expanded }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
                Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }, tint = PlayerCrimson)
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(220)) + fadeIn(tween(160)), exit = shrinkVertically(tween(180)) + fadeOut(tween(100))) { Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
        }
    }
}

/** Shared compact sheet action; destructive actions are visually distinct from normal navigation. */
@Composable internal fun MusicActionRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    forward: Boolean = false
) {
    val tint = if (destructive) MobileUi.accent else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).glassTouch(onClick, enabled)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint.copy(alpha = if (enabled) .85f else .3f))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = tint.copy(alpha = if (enabled) 1f else .4f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (forward) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact real-content rows keep Home's primary download controls near the top. */
@Composable internal fun MusicRecentList(title: String, tracks: List<LocalTrack>, empty: String, onAll: () -> Unit, onTrack: (LocalTrack) -> Unit) {
    MusicSectionTitle(title, onAction = onAll)
    if (tracks.isEmpty()) {
        Text(empty, Modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Column {
        tracks.take(3).forEach { track ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).glassTouch({ onTrack(track) }, track.available).padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrackArtwork(track, Modifier.size(46.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${track.artist} · ${track.format}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(durationLabel(track.durationMs / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (track != tracks.take(3).last()) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
internal fun MusicPillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit,
    enabled: Boolean = true, primary: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val foreground = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val fill = if (primary) MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .30f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .09f else .035f)
    Box(modifier.heightIn(min = 48.dp).clip(shape).glassTouch(onClick, enabled), contentAlignment = Alignment.Center) {
        Surface(Modifier.height(36.dp), shape = shape, color = fill,
            border = if (primary) null else BorderStroke(.65.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .20f))) {
            Row(Modifier.padding(horizontal = 15.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(17.dp), tint = foreground.copy(alpha = if (enabled) 1f else .5f))
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = foreground.copy(alpha = if (enabled) 1f else .5f), maxLines = 1)
            }
        }
    }
}
