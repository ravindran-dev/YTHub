package dev.ravindran.ythub.mobile

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/** Cache hashes against provider size/mtime; readability is probed on every reconciliation. */
object DeviceFileVerifier {
    fun verify(context:Context,track:LocalTrack,expectedSize:Long?=null,checksum:String?=null):DeviceFileEvidence {
        val preferences=context.getSharedPreferences("verified-media-v1",0)
        return try {
            val uri=Uri.parse(track.uri)
            var modified=0L
            runCatching {context.contentResolver.query(uri,arrayOf("last_modified"),null,null,null)?.use {if(it.moveToFirst())modified=it.getLong(0)}}
            context.contentResolver.openAssetFileDescriptor(uri,"r")?.use { descriptor ->
                val size=descriptor.length
                val matches=expectedSize==null || (size>=0 && size==expectedSize)
                val signature="$size:$modified:${checksum.orEmpty()}"
                val cached=preferences.getString(track.id,null)==signature && modified>0
                var integrity=matches
                if(matches && checksum!=null && !cached) {
                    val digest=MessageDigest.getInstance("SHA-256")
                    context.contentResolver.openInputStream(uri)?.use {input ->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}} ?: error("Unreadable file")
                    integrity=digest.digest().joinToString(""){"%02x".format(it)}==checksum
                }
                if(integrity)preferences.edit().putString(track.id,signature).apply()
                DeviceFileEvidence(track.id,true,integrity)
            } ?: DeviceFileEvidence(track.id,false)
        } catch(_:Exception) {DeviceFileEvidence(track.id,false)}
    }
}
