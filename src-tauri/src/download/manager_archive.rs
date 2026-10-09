use super::*;
impl Manager {
    pub(super) async fn schedule_archives(self: &Arc<Self>, app: &AppHandle) -> Result<(), String> {
        let batches = self.store.db.lock().unwrap().batches.clone();
        for batch in batches {
            if !matches!(batch.status, Status::Queued | Status::Interrupted) {
                continue;
            }
            let store = self.store.clone();
            let id = batch.id.clone();
            let (total, done, failed, cancelled) =
                tokio::task::spawn_blocking(move || store.batch_summary(&id))
                    .await
                    .map_err(|e| e.to_string())??;
            let ready = batch.discovered == batch.total_items
                && total == batch.total_items
                && done + failed + cancelled == total;
            {
                let mut db = self.store.db.lock().unwrap();
                let throughput = db
                    .jobs
                    .iter()
                    .filter(|j| j.playlist_id.as_ref() == Some(&batch.id))
                    .filter_map(|j| j.speed)
                    .sum();
                if let Some(b) = db.batches.iter_mut().find(|b| b.id == batch.id) {
                    b.completed_items = done;
                    b.failed_items = failed;
                    b.cancelled_items = cancelled;
                    b.progress = if b.total_items > 0 {
                        (done + failed + cancelled) as f64 / b.total_items as f64 * 100.0
                    } else {
                        0.0
                    };
                    b.throughput = throughput;
                }
            }
            if !ready || batch.status == Status::Interrupted {
                continue;
            }
            if !batch.create_zip || (failed + cancelled > 0 && !batch.settings.zip_partial) {
                {
                    let mut db = self.store.db.lock().unwrap();
                    if let Some(b) = db.batches.iter_mut().find(|b| b.id == batch.id) {
                        b.status = if failed + cancelled > 0 {
                            Status::Failed
                        } else {
                            Status::Completed
                        };
                        b.error = (failed + cancelled > 0).then(|| {
                            "Some items failed. Retry failed items to complete this playlist."
                                .into()
                        });
                    }
                }
                self.persist().await?;
                self.store.emit(app, "state:changed");
                continue;
            }
            if self.resources.metrics.lock().unwrap().pressure {
                continue;
            }
            let Ok(permit) = self.archive_slots.clone().try_acquire_owned() else {
                continue;
            };
            let cancel = Arc::new(AtomicBool::new(false));
            let mut snapshot = batch.clone();
            snapshot.failed_items = failed;
            snapshot.cancelled_items = cancelled;
            {
                let mut running = self.running.lock().unwrap();
                let mut db = self.store.db.lock().unwrap();
                let Some(b) = db.batches.iter_mut().find(|b| b.id == batch.id) else {
                    continue;
                };
                if b.status != Status::Queued {
                    continue;
                }
                b.status = Status::Zipping;
                running.insert(
                    batch.id.clone(),
                    Running {
                        phase: Phase::Archive,
                        cancel: self.stop.child_token(),
                        archive_cancel: cancel.clone(),
                        pid: None,
                        started: now(),
                    },
                );
            }
            let result = self
                .channels
                .lock()
                .unwrap()
                .as_ref()
                .ok_or("Archive worker not started")?
                .archive
                .try_send(ArchiveWork {
                    batch: snapshot,
                    cancel,
                    _permit: permit,
                });
            if result.is_err() {
                self.running.lock().unwrap().remove(&batch.id);
                if let Some(b) = self
                    .store
                    .db
                    .lock()
                    .unwrap()
                    .batches
                    .iter_mut()
                    .find(|b| b.id == batch.id)
                {
                    b.status = Status::Queued;
                }
            }
            self.persist().await?;
        }
        Ok(())
    }
    pub(super) async fn run_archive(self: &Arc<Self>, app: AppHandle, work: ArchiveWork) {
        let store = self.store.clone();
        let resources = self.resources.clone();
        let batch = work.batch.clone();
        let cancel = work.cancel.clone();
        let owner = self.clone();
        let a = app.clone();
        let result = tokio::task::spawn_blocking(move || {
            let files = store.batch_files(&batch.id)?;
            let partial = batch.failed_items + batch.cancelled_items > 0;
            let destination = PathBuf::from(&batch.settings.zip_dir).join(format!(
                "{}{} [{}]-{}.zip",
                storage::safe_name(&batch.title),
                if partial { " (incomplete)" } else { "" },
                batch.id.get(..8).unwrap_or(&batch.id),
                timestamp()
            ));
            let bytes = files
                .iter()
                .map(|(_, p)| std::fs::metadata(p).map(|v| v.len()).unwrap_or(0))
                .sum::<u64>();
            let _lease = resources.reserve(
                &[(PathBuf::from(&batch.settings.zip_dir), bytes)],
                batch.settings.disk_safety_mb * 1024 * 1024,
            )?;
            let mut last = Instant::now();
            storage::writable(&batch.settings.zip_dir)?;
            archive::create_zip_checked(
                &files,
                &destination,
                &cancel,
                batch.settings.disk_safety_mb * 1024 * 1024,
                |p| {
                    if last.elapsed() >= Duration::from_millis(200) {
                        last = Instant::now();
                        if let Some(b) = owner
                            .store
                            .db
                            .lock()
                            .unwrap()
                            .batches
                            .iter_mut()
                            .find(|b| b.id == batch.id)
                        {
                            if b.status == Status::Zipping {
                                b.progress = p;
                            }
                        }
                        owner.store.emit_batch(&a, &batch.id);
                    }
                },
            )?;
            Ok::<_, String>(destination.to_string_lossy().into_owned())
        })
        .await
        .map_err(|e| e.to_string())
        .and_then(|v| v);
        let mut success = false;
        {
            let mut db = self.store.db.lock().unwrap();
            if let Some(b) = db.batches.iter_mut().find(|b| b.id == work.batch.id) {
                if !work.cancel.load(Ordering::SeqCst) && b.status == Status::Zipping {
                    match result {
                        Ok(path) => {
                            b.output_path = Some(path);
                            b.progress = 100.0;
                            b.status = if b.failed_items + b.cancelled_items > 0 {
                                Status::Failed
                            } else {
                                Status::Completed
                            };
                            success = true;
                        }
                        Err(e) => {
                            b.status = Status::Failed;
                            b.error = Some(e);
                        }
                    }
                }
            }
        }
        self.running.lock().unwrap().remove(&work.batch.id);
        let _ = self.persist().await;
        self.store.emit(&app, "state:changed");
        if success
            && !work.batch.settings.keep_folder
            && work.batch.failed_items + work.batch.cancelled_items == 0
        {
            let folder = work.batch.folder.clone();
            let _ = tokio::task::spawn_blocking(move || std::fs::remove_dir_all(folder)).await;
        }
        drop(work);
        self.wake();
    }
}
