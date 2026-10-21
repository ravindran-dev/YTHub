package dev.ravindran.ythub.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class MusicStorageUsage(val audio: Long = 0, val video: Long = 0, val archives: Long = 0, val imported: Long = 0, val artwork: Long = 0, val temporary: Long = 0) {
    val total get() = audio + video + archives + imported + artwork + temporary
}
object MusicFileAccess {
    fun size(context: Context, value: String): Long = runCatching {
        context.contentResolver.query(Uri.parse(value), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).coerceAtLeast(0) else 0L } ?: 0L
    }.getOrDefault(0)
    fun storage(context: Context): MusicStorageUsage {
        val jobs = DownloadStore.items.value
        val audio = jobs.filter { it.audio }.flatMap { it.uris }.distinct()
        val video = jobs.filterNot { it.audio }.flatMap { it.uris }.distinct()
        val archives = jobs.map { it.zipUri }.filter { it.isNotBlank() }.distinct()
        val imported = File(context.filesDir, "music/imports").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return MusicStorageUsage(audio.sumOf { size(context, it) }, video.sumOf { size(context, it) }, archives.sumOf { size(context, it) }, imported, MusicRepository.artworkBytes(), DownloadStore.temporaryBytes())
    }
}
