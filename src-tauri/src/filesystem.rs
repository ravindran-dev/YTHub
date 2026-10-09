use std::path::Path;
pub fn replace(from: &Path, to: &Path) -> Result<(), String> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{
            MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
        };
        let a: Vec<u16> = from.as_os_str().encode_wide().chain(Some(0)).collect();
        let b: Vec<u16> = to.as_os_str().encode_wide().chain(Some(0)).collect();
        if unsafe {
            MoveFileExW(
                a.as_ptr(),
                b.as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        } == 0
        {
            return Err(format!(
                "Cannot commit completed output (it may be locked): {}",
                std::io::Error::last_os_error()
            ));
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        std::fs::rename(from, to).map_err(|e| e.to_string())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn atomic_replace_unicode_and_failed_commit_preserves_old() {
        let d = tempfile::tempdir().unwrap();
        let old = d.path().join("தமிழ் [song].mp3");
        let staged = d.path().join("new.part");
        std::fs::write(&old, b"old completed").unwrap();
        assert!(replace(&d.path().join("missing.part"), &old).is_err());
        assert_eq!(std::fs::read(&old).unwrap(), b"old completed");
        std::fs::write(&staged, b"new completed").unwrap();
        replace(&staged, &old).unwrap();
        assert_eq!(std::fs::read(&old).unwrap(), b"new completed");
        assert!(!staged.exists());
    }
}
