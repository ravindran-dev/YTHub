use super::*;
impl Manager {
    pub(super) async fn run_work(self: &Arc<Self>, app: AppHandle, work: Work) {
        let result = std::panic::AssertUnwindSafe(self.execute(&app, &work))
            .catch_unwind()
            .await
            .unwrap_or_else(|_| {
                Err("Worker encountered an internal error; partial data retained".into())
            });
        let (mut terminal, mut success, mut retry) = (false, false, false);
        {
            let _running = self.running.lock().unwrap();
            let mut db = self.store.db.lock().unwrap();
            if let Some(j) = db.jobs.iter_mut().find(|j| j.id == work.job.id) {
                if work.cancel.is_cancelled()
                    || matches!(
                        j.status,
                        Status::Paused
                            | Status::Interrupted
                            | Status::Cancelling
                            | Status::Cancelled
                    )
                {
                    if j.status == Status::Cancelling {
                        j.status = Status::Cancelled;
                        j.completed_at = Some(now());
                    }
                    j.speed = None;
                    j.eta = None;
                } else {
                    match result {
                        Ok(ResultStage::Media(stage)) => {
                            j.staging = Some(stage);
                            j.status = j
                                .status
                                .advance(Status::DownloadComplete)
                                .unwrap_or(Status::Failed);
                            j.progress = 100.0;
                            j.speed = None;
                            j.eta = None;
                        }
                        Ok(ResultStage::Finished(path, skipped)) => {
                            j.status = j
                                .status
                                .advance(if skipped {
                                    Status::Skipped
                                } else {
                                    Status::Completed
                                })
                                .unwrap_or(Status::Failed);
                            j.output_path = Some(path);
                            j.progress = 100.0;
                            j.error = None;
                            j.error_code = None;
                            j.speed = None;
                            j.eta = None;
                            j.completed_at = Some(now());
                            success = true;
                            terminal = true;
                        }
                        Err(ref error) => {
                            let kind = ErrorKind::classify(error);
                            if kind == ErrorKind::RateLimited {
                                self.adaptive.lock().unwrap().throttle(host(j), timestamp());
                            }
                            j.attempts += 1;
                            j.error = Some(engine::redact(error, &j.settings));
                            j.error_code = Some(kind.code());
                            j.speed = None;
                            j.eta = None;
                            let seed = uuid::Uuid::new_v4().as_u128() as u64;
                            if let Some(delay) = download::retry::delay(
                                kind,
                                j.attempts,
                                j.settings.retries,
                                j.settings.retry_backoff,
                                seed,
                            ) {
                                j.retry_at = Some(timestamp() + delay as i64);
                                j.status = j
                                    .status
                                    .advance(Status::RetryWaiting)
                                    .unwrap_or(Status::Failed);
                                retry = true;
                            } else {
                                j.status =
                                    j.status.advance(Status::Failed).unwrap_or(Status::Failed);
                                j.completed_at = Some(now());
                                terminal = true;
                            }
                        }
                    }
                }
            }
        }
        if retry {
            self.metrics.lock().unwrap().retry_count += 1;
        }
        self.running.lock().unwrap().remove(&work.job.id);
        if let Err(e) = self.persist().await {
            let _ = app.emit("engine:error", format!("Unable to persist task: {e}"));
            self.shutting_down.store(true, Ordering::SeqCst);
            self.stop.cancel();
        }
        self.store.emit_job(&app, &work.job.id, "state:job");
        if success {
            let path = work.job.temp_dir.clone();
            let _ = tokio::task::spawn_blocking(move || std::fs::remove_dir_all(path)).await;
            self.adaptive.lock().unwrap().recovered(host(&work.job));
        }
        if terminal && work.job.settings.notifications && work.job.playlist_id.is_none() {
            let _ = app
                .notification()
                .builder()
                .title(if success {
                    "Download complete"
                } else {
                    "Download failed"
                })
                .body(&work.job.title)
                .show();
        }
        self.store.log(&format!(
            "task {} phase {} finished",
            work.job.id,
            if work.phase == Phase::Download {
                "download"
            } else {
                "processing"
            }
        ));
        drop(work);
        self.trim();
        self.wake();
    }
    async fn execute(&self, app: &AppHandle, work: &Work) -> Result<ResultStage, String> {
        let job = &work.job;
        if work.cancel.is_cancelled() {
            return Err("Cancelled".into());
        }
        let f = job.clone();
        let r = self.resources.clone();
        let settings = self.store.db.lock().unwrap().settings.clone();
        let lease = tokio::task::spawn_blocking(move || {
            storage::writable(&f.output_dir)?;
            storage::writable(&f.temp_dir)?;
            r.download_reservation(&f, &settings)
        })
        .await
        .map_err(|e| e.to_string())??;
        self.update(&job.id, |j| {
            let next = if work.phase == Phase::Download {
                Status::Downloading
            } else if j.media_type == "audio" {
                Status::Converting
            } else {
                Status::Merging
            };
            if let Ok(status) = j.status.advance(next) {
                j.status = status;
            }
            j.started_at.get_or_insert_with(now);
            j.error = None;
        });
        self.persist().await?;
        self.store.emit_job(app, &job.id, "state:job");
        let mut meter = download::progress::ProgressMeter::new(job.settings.progress_hz);
        let mut process_emit = Instant::now();
        let line = |line: &str| {
            if let Some(pid) = line.strip_prefix("YTHUB_PID:").and_then(|v| v.parse().ok()) {
                if let Some(r) = self.running.lock().unwrap().get_mut(&job.id) {
                    r.pid = Some(pid);
                }
                return;
            }
            if let Some(code) = line.strip_prefix("YTHUB_EXIT:") {
                self.store.log(&format!(
                    "task {} command phase {} exit {code}",
                    job.id,
                    if work.phase == Phase::Download {
                        "download"
                    } else {
                        "processing"
                    }
                ));
                return;
            }
            if let Some(p) = engine::parse_progress(line) {
                let downloaded = p["downloaded_bytes"].as_u64().unwrap_or(0);
                let total = p["total_bytes"]
                    .as_u64()
                    .or(p["total_bytes_estimate"].as_u64());
                let (bytes, speed, eta, emit) = meter.update(downloaded, total, Instant::now());
                self.update(&job.id, |j| {
                    j.downloaded_bytes = bytes;
                    j.total_bytes = total;
                    j.progress = total
                        .filter(|n| *n > 0)
                        .map(|n| downloaded as f64 / n as f64 * 100.0)
                        .unwrap_or(0.0)
                        .min(100.0);
                    j.speed = speed;
                    j.instantaneous_speed = p["speed"].as_f64();
                    j.eta = eta;
                });
                if emit {
                    self.store.emit_job(app, &job.id, "state:job");
                }
            } else if let Some(us) = line
                .strip_prefix("out_time_us=")
                .and_then(|v| v.parse::<f64>().ok())
            {
                self.update(&job.id, |j| {
                    j.progress = j
                        .duration
                        .filter(|v| *v > 0.0)
                        .map(|duration| (us / 1_000_000.0 / duration * 100.0).min(99.9))
                        .unwrap_or(0.0);
                });
                if process_emit.elapsed()
                    >= Duration::from_millis(1000 / job.settings.progress_hz.max(1) as u64)
                {
                    process_emit = Instant::now();
                    self.store.emit_job(app, &job.id, "state:job");
                }
            }
        };
        let result = if work.phase == Phase::Download {
            let yt = engine::resolve(app, &job.settings, "yt-dlp")?;
            let deno = engine::resolve(app, &job.settings, "deno").ok();
            download::yt_dlp::download(job, &yt, deno.as_deref(), &work.cancel, line)
                .await
                .map(ResultStage::Media)
        } else {
            let ff = engine::resolve(app, &job.settings, "ffmpeg")?;
            let probe = engine::resolve(app, &job.settings, "ffprobe")?;
            download::ffmpeg::process(
                job,
                job.staging.as_ref().ok_or("Missing staged media")?,
                &ff,
                &probe,
                &work.cancel,
                line,
            )
            .await
            .map(|(path, skip)| ResultStage::Finished(path, skip))
        };
        drop(lease);
        result
    }
    #[allow(clippy::field_reassign_with_default)] // Built in stages under separate, short-lived locks.
    pub(super) async fn refresh_metrics(&self, app: &AppHandle) {
        let store = self.store.clone();
        let totals = tokio::task::spawn_blocking(move || store.totals())
            .await
            .ok()
            .and_then(Result::ok)
            .unwrap_or_default();
        let (mut next, s) = {
            let running = self.running.lock().unwrap();
            let db = self.store.db.lock().unwrap();
            let mut m = Diagnostics::default();
            m.active_workers = running
                .values()
                .filter(|r| r.phase == Phase::Download)
                .count();
            m.processing_workers = running
                .values()
                .filter(|r| r.phase == Phase::Process)
                .count();
            m.zip_jobs = running
                .values()
                .filter(|r| r.phase == Phase::Archive)
                .count();
            m.active_yt_dlp = running
                .values()
                .filter(|r| r.phase == Phase::Download && r.pid.is_some())
                .count();
            m.active_ffmpeg = running
                .values()
                .filter(|r| r.phase == Phase::Process && r.pid.is_some())
                .count();
            m.queued_tasks = db
                .jobs
                .iter()
                .filter(|j| {
                    matches!(
                        j.status,
                        Status::Queued
                            | Status::RetryWaiting
                            | Status::WaitingForProcessor
                            | Status::DownloadComplete
                    )
                })
                .count()
                + db.batches
                    .iter()
                    .filter(|b| !b.status.terminal())
                    .map(|b| b.total_items.saturating_sub(b.discovered))
                    .sum::<usize>();
            m.throughput = db.jobs.iter().filter_map(|j| j.speed).sum();
            m.processes = running
                .iter()
                .map(|(id, r)| ProcessInfo {
                    task_id: id.clone(),
                    pid: r.pid,
                    command: match r.phase {
                        Phase::Download => "yt-dlp",
                        Phase::Process => "FFmpeg",
                        Phase::Archive => "ZIP",
                    }
                    .into(),
                    started_at: r.started.clone(),
                })
                .collect();
            (m, db.settings.clone())
        };
        next.configured_concurrency = s.concurrency;
        next.current_concurrency = self.adaptive.lock().unwrap().limit;
        next.throttled_hosts = self.adaptive.lock().unwrap().cooling(timestamp());
        next.resources = self.resources.metrics.lock().unwrap().clone();
        (
            next.total_tasks,
            next.completed_tasks,
            next.failed_tasks,
            next.cancelled_tasks,
            next.bytes_downloaded,
        ) = totals;
        {
            let mut m = self.metrics.lock().unwrap();
            next.retry_count = m.retry_count;
            next.peak_speed = m.peak_speed.max(next.throughput);
            *m = next.clone();
        }
        let _ = app.emit("engine:metrics", &next);
        let batches = self
            .store
            .db
            .lock()
            .unwrap()
            .batches
            .iter()
            .filter(|b| !b.status.terminal())
            .map(|b| b.id.clone())
            .collect::<Vec<_>>();
        for id in batches {
            self.store.emit_batch(app, &id);
        }
    }
}
