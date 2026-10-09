use std::path::{Path, PathBuf};

fn shell_path(path: &Path) -> Result<PathBuf, String> {
    let absolute =
        std::fs::canonicalize(path).map_err(|e| format!("Cannot open the file or folder: {e}"))?;
    #[cfg(windows)]
    {
        // Shell associations expect ordinary Windows paths, including UNC shares.
        let text = absolute.to_string_lossy().replace('/', "\\");
        let text = if let Some(unc) = text.strip_prefix("\\\\?\\UNC\\") {
            format!("\\\\{unc}")
        } else {
            text.strip_prefix("\\\\?\\").unwrap_or(&text).to_string()
        };
        Ok(PathBuf::from(text))
    }
    #[cfg(not(windows))]
    Ok(absolute)
}

pub fn open_path(path: &Path) -> Result<(), String> {
    let path = shell_path(path)?;
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::UI::{Shell::ShellExecuteW, WindowsAndMessaging::SW_SHOWNORMAL};
        let target: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        let operation: Vec<u16> = "open".encode_utf16().chain(Some(0)).collect();
        // The path is a separate UTF-16 parameter, never command or shell text.
        let result = unsafe {
            ShellExecuteW(
                std::ptr::null_mut(),
                operation.as_ptr(),
                target.as_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                SW_SHOWNORMAL,
            )
        } as isize;
        if result > 32 {
            return Ok(());
        }
        let reason = match result {
            2 | 3 => "The file or folder no longer exists.",
            5 => "Windows denied access to this file or folder.",
            31 => {
                "Choose a default player for this media format in Windows Settings, then try again."
            }
            27 => "The Windows file association is incomplete. Choose a default media player.",
            _ => "Windows could not open this file or folder. Check its default application.",
        };
        Err(format!("{reason} (Windows shell error {result})"))
    }
    #[cfg(not(windows))]
    {
        let _ = path;
        Err("Opening local output is supported on Windows.".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shell_paths_preserve_spaces_unicode_and_brackets() {
        let folder = tempfile::tempdir().unwrap();
        let path = folder.path().join("தமிழ் track [video-id] & mix.mp3");
        std::fs::write(&path, b"media").unwrap();
        // Matches paths emitted by yt-dlp, rather than just Rust PathBuf input.
        let engine_path = PathBuf::from(path.to_string_lossy().replace('\\', "/"));
        let prepared = shell_path(&engine_path).unwrap();
        assert_eq!(prepared.file_name(), path.file_name());
        assert!(prepared.is_absolute());
        assert!(prepared.is_file());
        #[cfg(windows)]
        {
            assert!(!prepared.to_string_lossy().contains('/'));
            assert!(!prepared.to_string_lossy().starts_with("\\\\?\\"));
        }
    }
    #[test]
    fn missing_outputs_report_error() {
        let folder = tempfile::tempdir().unwrap();
        assert!(shell_path(&folder.path().join("removed.mp4")).is_err());
    }
}
