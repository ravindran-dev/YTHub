use std::{
    fs::{self, File},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

#[cfg(test)]
pub fn create_zip(
    files: &[(usize, PathBuf)],
    destination: &Path,
    cancel: &AtomicBool,
    mut progress: impl FnMut(f64),
) -> Result<(), String> {
    create_zip_checked(files, destination, cancel, 0, &mut progress)
}
pub fn create_zip_checked(
    files: &[(usize, PathBuf)],
    destination: &Path,
    cancel: &AtomicBool,
    safety: u64,
    mut progress: impl FnMut(f64),
) -> Result<(), String> {
    let partial = destination.with_extension("zip.part");
    let mut disk_check = std::time::Instant::now();
    let result = (|| {
        if destination.exists() {
            return Err("Archive already exists; choose a new archive name.".into());
        }
        let mut archive = ZipWriter::new(File::create(&partial).map_err(|e| e.to_string())?);
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Stored)
            .large_file(true);
        let mut ordered = files.to_vec();
        ordered.sort_by_key(|f| f.0);
        let total: u64 = ordered
            .iter()
            .map(|(_, p)| fs::metadata(p).map(|m| m.len()).unwrap_or(0))
            .sum();
        let mut done = 0u64;
        let mut buffer = vec![0u8; 1024 * 1024];
        for (_, path) in ordered {
            if cancel.load(Ordering::SeqCst) {
                return Err("ZIP creation cancelled.".into());
            }
            let name = path
                .file_name()
                .ok_or("Invalid media filename")?
                .to_string_lossy();
            archive
                .start_file(name, options)
                .map_err(|e| e.to_string())?;
            let mut file = File::open(path).map_err(|e| e.to_string())?;
            loop {
                if cancel.load(Ordering::SeqCst) {
                    return Err("ZIP creation cancelled.".into());
                }
                if disk_check.elapsed() >= std::time::Duration::from_secs(1) {
                    disk_check = std::time::Instant::now();
                    let free =
                        fs2::available_space(destination.parent().ok_or("Invalid archive folder")?)
                            .map_err(|e| e.to_string())?;
                    if free < safety {
                        return Err("Disk full: ZIP free-space reserve reached".into());
                    }
                }
                let bytes = io::Read::read(&mut file, &mut buffer).map_err(|e| e.to_string())?;
                if bytes == 0 {
                    break;
                }
                archive
                    .write_all(&buffer[..bytes])
                    .map_err(|e| e.to_string())?;
                done += bytes as u64;
                progress(if total == 0 {
                    100.0
                } else {
                    done as f64 / total as f64 * 100.0
                });
            }
        }
        archive
            .finish()
            .map_err(|e| e.to_string())?
            .sync_all()
            .map_err(|e| e.to_string())?;
        if cancel.load(Ordering::SeqCst) {
            return Err("ZIP creation cancelled.".into());
        }
        fs::rename(&partial, destination).map_err(|e| e.to_string())?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(partial);
    }
    result
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn real_streamed_zip_in_order() {
        let d = tempfile::tempdir().unwrap();
        let a = d.path().join("01 - 你好.mp3");
        let b = d.path().join("02 - Song.mp3");
        fs::write(&a, b"audio-a").unwrap();
        fs::write(&b, b"audio-b").unwrap();
        let target = d.path().join("Playlist.zip");
        create_zip(&[(2, b), (1, a)], &target, &AtomicBool::new(false), |_| {}).unwrap();
        let mut z = zip::ZipArchive::new(File::open(target).unwrap()).unwrap();
        assert_eq!(z.len(), 2);
        assert_eq!(z.by_index(0).unwrap().name(), "01 - 你好.mp3");
    }
    #[test]
    fn cancelled_zip_leaves_no_archive() {
        let d = tempfile::tempdir().unwrap();
        let f = d.path().join("file");
        fs::write(&f, b"abc").unwrap();
        let target = d.path().join("output.zip");
        assert!(create_zip(&[(0, f)], &target, &AtomicBool::new(true), |_| {}).is_err());
        assert!(!target.exists());
        assert!(!target.with_extension("zip.part").exists());
    }
}
