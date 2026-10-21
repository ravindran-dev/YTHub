package dev.ravindran.ythub.mobile

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object MusicTestFixtures {
    /** Twenty seconds of silent PCM for decoder/session tests, never bundled in production. */
    fun audio(context: Context, name: String): Uri {
        val target = File(context.filesDir, "music/imports/tests/$name.wav").apply { parentFile?.mkdirs() }
        val dataBytes = 8000 * 2 * 20
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(dataBytes)
        while (buffer.hasRemaining()) buffer.putShort(0)
        target.writeBytes(buffer.array())
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }
}
