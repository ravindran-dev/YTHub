package dev.ravindran.ythub.mobile

import java.io.DataInputStream
import java.io.InputStream

/** Bounded ID3v2.3/v2.4 USLT reader. Unsupported/compressed tags return no lyrics. */
object EmbeddedLyrics {
    fun read(stream: InputStream): String = runCatching {
        val input = DataInputStream(stream)
        val header = ByteArray(10); input.readFully(header)
        if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3" || header[3].toInt() !in 3..4) return ""
        val version = header[3].toInt()
        // Unsynchronisation and extended headers need a fuller parser; do not misinterpret them.
        if ((header[5].toInt() and 0xc0) != 0) return ""
        val size = syncSafe(header, 6)
        if (size !in 10..8 * 1024 * 1024) return ""
        val body = ByteArray(size); input.readFully(body)
        var offset = 0
        while (offset + 10 <= body.size) {
            val id = String(body, offset, 4, Charsets.ISO_8859_1)
            val length = if (version == 4) syncSafe(body, offset + 4) else bigEndian(body, offset + 4)
            if (length <= 0 || length > body.size - offset - 10) return ""
            val flags = body[offset + 9].toInt() and 0xff
            if (id == "USLT" && flags == 0) return decode(body.copyOfRange(offset + 10, offset + 10 + length))
            offset += 10 + length
        }
        ""
    }.getOrDefault("")
    private fun decode(bytes: ByteArray): String {
        if (bytes.size < 5) return ""
        val encoding = bytes[0].toInt()
        val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> return "" }
        val width = if (encoding in 1..2) 2 else 1
        var start = 4
        while (start + width <= bytes.size) {
            if (bytes[start] == 0.toByte() && (width == 1 || bytes[start + 1] == 0.toByte())) {
                start += width
                return String(bytes, start, bytes.size - start, charset).trimEnd('\u0000').trim()
            }
            start += width
        }
        return ""
    }
    private fun syncSafe(bytes: ByteArray, offset: Int): Int {
        var value = 0
        repeat(4) { index -> val b = bytes[offset + index].toInt() and 0xff; require(b < 128); value = (value shl 7) or b }
        return value
    }
    private fun bigEndian(bytes: ByteArray, offset: Int): Int {
        var value = 0L
        repeat(4) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
        require(value <= Int.MAX_VALUE); return value.toInt()
    }
}
