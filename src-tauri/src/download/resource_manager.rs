use crate::models::{Job, Settings};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};
#[derive(Clone, Debug, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Resources {
    pub cpu_percent: f32,
    pub memory_available: u64,
    pub memory_used: u64,
    pub pressure: bool,
}
#[derive(Default)]
pub struct ResourceManager {
    reserved: Mutex<HashMap<String, u64>>,
    pub metrics: Mutex<Resources>,
}
pub struct Reservation {
    owner: Arc<ResourceManager>,
    values: Vec<(String, u64)>,
}
impl Drop for Reservation {
    fn drop(&mut self) {
        let mut m = self.owner.reserved.lock().unwrap();
        for (k, v) in &self.values {
            if let Some(n) = m.get_mut(k) {
                *n = n.saturating_sub(*v);
            }
        }
    }
}
fn volume(path: &Path) -> String {
    #[cfg(windows)]
    {
        use std::path::Component;
        if let Some(Component::Prefix(p)) = path.components().next() {
            return p.as_os_str().to_string_lossy().to_lowercase();
        }
    }
    path.to_string_lossy().into_owned()
}
pub fn estimate(job: &Job) -> u64 {
    let rate = if job.media_type == "audio" {
        160_000.0
    } else {
        2_000_000.0
    };
    (job.duration.unwrap_or(120.0) * rate).clamp(16_777_216.0, u64::MAX as f64) as u64
}
impl ResourceManager {
    pub fn reserve(
        self: &Arc<Self>,
        paths: &[(PathBuf, u64)],
        safety: u64,
    ) -> Result<Reservation, String> {
        let mut totals: HashMap<String, (PathBuf, u64)> = HashMap::new();
        for (p, n) in paths {
            let v = totals.entry(volume(p)).or_insert((p.clone(), 0));
            v.1 = v.1.saturating_add(*n);
        }
        let mut reserved = self.reserved.lock().map_err(|e| e.to_string())?;
        for (k, (p, n)) in &totals {
            let free = fs2::available_space(p).map_err(|e| format!("Disk unavailable: {e}"))?;
            if free
                < safety
                    .saturating_add(*n)
                    .saturating_add(*reserved.get(k).unwrap_or(&0))
            {
                return Err("Insufficient disk space for this job and active reservations.".into());
            }
        }
        let mut values = vec![];
        for (k, (_, n)) in totals {
            *reserved.entry(k.clone()).or_default() += n;
            values.push((k, n));
        }
        Ok(Reservation {
            owner: self.clone(),
            values,
        })
    }
    pub fn check(paths: &[PathBuf], safety: u64) -> Result<(), String> {
        for p in paths {
            let free = fs2::available_space(p).map_err(|e| format!("Disk unavailable: {e}"))?;
            if free < safety {
                return Err("Critically low disk space. Partial data retained; choose a drive with free space before retrying.".into());
            }
        }
        Ok(())
    }
    pub fn sample(&self, system: &mut sysinfo::System) {
        system.refresh_cpu_usage();
        system.refresh_memory();
        let cpu = system.global_cpu_usage();
        let available = system.available_memory();
        let pressure = cpu > 92.0 || available < 512 * 1024 * 1024;
        *self.metrics.lock().unwrap() = Resources {
            cpu_percent: cpu,
            memory_available: available,
            memory_used: system.used_memory(),
            pressure,
        };
    }
    pub fn download_reservation(
        self: &Arc<Self>,
        job: &Job,
        s: &Settings,
    ) -> Result<Reservation, String> {
        self.reserve(
            &[
                (PathBuf::from(&job.temp_dir), estimate(job)),
                (PathBuf::from(&job.output_dir), estimate(job)),
            ],
            s.disk_safety_mb * 1024 * 1024,
        )
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reservations_release_and_low_disk_is_rejected() {
        let r = Arc::new(ResourceManager::default());
        let d = tempfile::tempdir().unwrap();
        let lease = r.reserve(&[(d.path().into(), 1024)], 0).unwrap();
        assert_eq!(r.reserved.lock().unwrap().values().sum::<u64>(), 1024);
        drop(lease);
        assert_eq!(r.reserved.lock().unwrap().values().sum::<u64>(), 0);
        assert!(r.reserve(&[(d.path().into(), u64::MAX / 2)], 1024).is_err());
    }
}
