package dev.ravindran.ythub.mobile
import org.junit.Assert.assertEquals
import org.junit.Test
class LibraryAvailabilityTest {
    @Test fun metadataAloneCannotPlayOrDownload() { assertEquals(FileAvailability.missing_local_no_cloud_file,LibraryAvailability.derive(AccountSongEvidence("a",false),null)) }
    @Test fun verifiedLocalFileDoesNotNeedRedownload() { assertEquals(FileAvailability.available_local_and_cloud,LibraryAvailability.derive(AccountSongEvidence("a",true),DeviceFileEvidence("a",true))) }
    @Test fun removedLocalFileCanUseCloudCopy() { assertEquals(FileAvailability.missing_local_downloadable,LibraryAvailability.derive(AccountSongEvidence("a",true),DeviceFileEvidence("a",false))) }
    @Test fun corruptLocalFileIsUnavailable() { assertEquals(FileAvailability.local_file_missing,LibraryAvailability.derive(AccountSongEvidence("a",false),DeviceFileEvidence("a",true,false))) }
    @Test fun failedDownloadRetainsRetryState() { assertEquals(FileAvailability.download_failed,LibraryAvailability.derive(AccountSongEvidence("a",true),null,FileAvailability.download_failed)) }
    @Test fun unsupportedFilesAreNotDownloadable() { assertEquals(FileAvailability.unsupported_format,LibraryAvailability.derive(AccountSongEvidence("a",true,false),null)) }
}

class CloudRetryPolicyTest {
    @Test fun retryDelayIsBounded() {assertEquals(2000L,cloudRetryDelay(0));assertEquals(4000L,cloudRetryDelay(1));assertEquals(256000L,cloudRetryDelay(100))}
}
