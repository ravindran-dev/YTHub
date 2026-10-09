/// Keep every downloader and descendant in an OS job. Closing the app (including
/// a crash) closes the job handle and Windows terminates the complete process tree.
pub struct ProcessTree {
    #[cfg(windows)]
    handle: isize,
}
impl ProcessTree {
    pub fn attach(pid: u32) -> Result<Self, String> {
        #[cfg(windows)]
        unsafe {
            use windows_sys::Win32::{
                Foundation::CloseHandle,
                System::{JobObjects::*, Threading::*},
            };
            let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if job.is_null() {
                return Err(format!(
                    "Cannot create Windows process job: {}",
                    std::io::Error::last_os_error()
                ));
            }
            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            if SetInformationJobObject(
                job,
                JobObjectExtendedLimitInformation,
                (&info as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
                std::mem::size_of_val(&info) as u32,
            ) == 0
            {
                let error = std::io::Error::last_os_error();
                CloseHandle(job);
                return Err(format!("Cannot configure process isolation: {error}"));
            }
            let process = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, pid);
            if process.is_null() {
                let error = std::io::Error::last_os_error();
                CloseHandle(job);
                return Err(format!("Cannot track download process: {error}"));
            }
            let assigned = AssignProcessToJobObject(job, process);
            let error = std::io::Error::last_os_error();
            CloseHandle(process);
            if assigned == 0 {
                CloseHandle(job);
                return Err(format!("Cannot isolate download process: {error}"));
            }
            Ok(Self {
                handle: job as isize,
            })
        }
        #[cfg(not(windows))]
        {
            let _ = pid;
            Ok(Self {})
        }
    }
}
impl Drop for ProcessTree {
    fn drop(&mut self) {
        #[cfg(windows)]
        unsafe {
            windows_sys::Win32::Foundation::CloseHandle(self.handle as _);
        }
    }
}
pub fn spawn_managed(
    command: &mut tokio::process::Command,
) -> Result<(tokio::process::Child, ProcessTree), String> {
    #[cfg(windows)]
    command.creation_flags(0x08000000 | 0x00000004);
    let mut child = command.spawn().map_err(|e| e.to_string())?;
    let pid = child.id().ok_or("Process has no PID")?;
    let tree = ProcessTree::attach(pid)?;
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Foundation::{CloseHandle, INVALID_HANDLE_VALUE},
            System::{Diagnostics::ToolHelp::*, Threading::*},
        };
        let snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
        if snapshot == INVALID_HANDLE_VALUE {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let mut entry: THREADENTRY32 = std::mem::zeroed();
        entry.dwSize = std::mem::size_of_val(&entry) as u32;
        let mut found = false;
        let mut ok = Thread32First(snapshot, &mut entry);
        while ok != 0 {
            if entry.th32OwnerProcessID == pid {
                let thread = OpenThread(THREAD_SUSPEND_RESUME, 0, entry.th32ThreadID);
                if !thread.is_null() {
                    found = ResumeThread(thread) != u32::MAX;
                    CloseHandle(thread);
                }
                break;
            }
            ok = Thread32Next(snapshot, &mut entry);
        }
        CloseHandle(snapshot);
        if !found {
            let _ = child.start_kill();
            return Err("Cannot resume isolated Windows process".into());
        }
    }
    Ok((child, tree))
}
#[cfg(all(test, windows))]
mod tests {
    use super::*;
    #[tokio::test]
    #[ignore = "requires provisioned FFmpeg executable"]
    async fn dropping_job_stops_real_process() {
        let ff = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("binaries/ffmpeg.exe");
        let mut child = crate::engine::command(ff)
            .args([
                "-hide_banner",
                "-loglevel",
                "error",
                "-re",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=1000",
                "-t",
                "60",
                "-f",
                "null",
                "-",
            ])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        let job = ProcessTree::attach(child.id().unwrap()).unwrap();
        assert!(child.try_wait().unwrap().is_none());
        drop(job);
        let _result = tokio::time::timeout(std::time::Duration::from_secs(5), child.wait())
            .await
            .unwrap()
            .unwrap();
        // Windows may report exit code 0 for JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.
        // The five-second bound (versus a 60-second input) verifies termination.
    }
}
