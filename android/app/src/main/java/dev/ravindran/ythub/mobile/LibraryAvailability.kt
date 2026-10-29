package dev.ravindran.ythub.mobile

/** Shared reconciliation rules: metadata is never evidence of an accessible file. */
enum class FileAvailability {
    available_local, available_cloud, available_local_and_cloud,
    missing_local_downloadable, missing_local_no_cloud_file,
    queued_for_download, downloading, download_failed, local_file_missing,
    verifying_file, unsupported_format
}
data class DeviceFileEvidence(val songId: String, val readable: Boolean, val sizeMatches: Boolean = true, val previouslyMapped: Boolean = true)
data class AccountSongEvidence(val songId: String, val confirmedCloudFile: Boolean, val supported: Boolean = true)
object LibraryAvailability {
    fun derive(song: AccountSongEvidence, local: DeviceFileEvidence?, task: FileAvailability? = null): FileAvailability {
        if(local?.readable == true && local.sizeMatches) return if(song.confirmedCloudFile) FileAvailability.available_local_and_cloud else FileAvailability.available_local
        if(task in setOf(FileAvailability.queued_for_download,FileAvailability.downloading,FileAvailability.verifying_file,FileAvailability.download_failed)) return task!!
        if(!song.supported) return FileAvailability.unsupported_format
        if(song.confirmedCloudFile) return FileAvailability.missing_local_downloadable
        return if(local?.previouslyMapped == true) FileAvailability.local_file_missing else FileAvailability.missing_local_no_cloud_file
    }
}
