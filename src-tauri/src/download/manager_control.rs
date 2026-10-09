use super::*;
impl Manager {
    pub async fn control(&self, app: &AppHandle, id: &str, action: &str) -> Result<(), String> {
        let present = self
            .store
            .db
            .lock()
            .unwrap()
            .jobs
            .iter()
            .any(|j| j.id == id);
        if !present {
            let store = self.store.clone();
            let key = id.to_string();
            let job = tokio::task::spawn_blocking(move || store.lookup(&key))
                .await
                .map_err(|e| e.to_string())??;
            self.store.db.lock().unwrap().jobs.push(job);
        }
        let mut remove = false;
        {
            let running = self.running.lock().unwrap();
            let mut db = self.store.db.lock().unwrap();
            let index = db
                .jobs
                .iter()
                .position(|j| j.id == id)
                .ok_or("Download not found in the current page")?;
            if action == "remove"
                && db.jobs[index].playlist_id.as_ref().is_some_and(|id| {
                    db.batches
                        .iter()
                        .any(|b| &b.id == id && !b.status.terminal())
                })
            {
                return Err(
                    "Finish or cancel the playlist before removing one of its items".into(),
                );
            }
            let j = &mut db.jobs[index];
            if ["high", "normal", "low"].contains(&action) {
                j.priority = match action {
                    "high" => Priority::High,
                    "low" => Priority::Low,
                    _ => Priority::Normal,
                };
            } else if action == "remove" {
                if running.contains_key(id)
                    || (!j.status.terminal()
                        && !matches!(j.status, Status::Paused | Status::Interrupted))
                {
                    return Err("Pause or finish a download before removing it".into());
                }
                remove = true;
                db.jobs.remove(index);
            } else {
                let next = j.status.transition(action, running.contains_key(id))?;
                j.status = if action == "cancel" && running.contains_key(id) {
                    Status::Cancelling
                } else {
                    next
                };
                j.speed = None;
                j.eta = None;
                j.retry_at = None;
                if action == "resume" || action == "retry" {
                    j.attempts = 0;
                    j.error = None;
                    let batch = j.playlist_id.clone();
                    if let Some(b) = db
                        .batches
                        .iter_mut()
                        .find(|b| Some(&b.id) == batch.as_ref())
                    {
                        b.status = Status::Queued;
                        b.error = None;
                    }
                }
                if let Some(r) = running.get(id) {
                    r.cancel.cancel();
                    r.archive_cancel.store(true, Ordering::SeqCst);
                }
            }
        }
        if remove {
            let store = self.store.clone();
            let id = id.to_string();
            tokio::task::spawn_blocking(move || store.delete_job(&id))
                .await
                .map_err(|e| e.to_string())??;
        }
        self.persist().await?;
        self.store.emit(app, "state:changed");
        self.wake();
        Ok(())
    }
    pub async fn control_batch(
        &self,
        app: &AppHandle,
        id: Option<&str>,
        action: &str,
    ) -> Result<(), String> {
        let (from, to) = match action {
            "resume" => (vec![Status::Paused, Status::Interrupted], Status::Queued),
            "retry" => (vec![Status::Failed, Status::Cancelled], Status::Queued),
            "pause" => (
                vec![
                    Status::Queued,
                    Status::RetryWaiting,
                    Status::WaitingForWorker,
                    Status::DownloadComplete,
                    Status::WaitingForProcessor,
                ],
                Status::Paused,
            ),
            "cancel" => (
                vec![
                    Status::Queued,
                    Status::RetryWaiting,
                    Status::WaitingForWorker,
                    Status::DownloadComplete,
                    Status::WaitingForProcessor,
                    Status::Paused,
                    Status::Interrupted,
                ],
                Status::Cancelled,
            ),
            _ => return Err("Unknown playlist action".into()),
        };
        // Close admission for this playlist before stopping its existing workers.
        {
            let running = self.running.lock().unwrap();
            let mut db = self.store.db.lock().unwrap();
            if ["resume", "retry"].contains(&action)
                && (db.jobs.iter().any(|j| {
                    id.is_none_or(|id| j.playlist_id.as_deref() == Some(id))
                        && matches!(
                            j.status,
                            Status::Paused
                                | Status::Interrupted
                                | Status::Failed
                                | Status::Cancelled
                        )
                        && running.contains_key(&j.id)
                }) || db
                    .batches
                    .iter()
                    .any(|b| id.is_none_or(|id| b.id == id) && running.contains_key(&b.id)))
            {
                return Err(
                    "Wait for stopping playlist workers before resuming or retrying".into(),
                );
            }
            for b in &mut db.batches {
                if id.is_none_or(|id| b.id == id) && (!b.status.terminal() || action == "retry") {
                    b.status = to.clone();
                    b.error = None;
                    if let Some(r) = running.get(&b.id) {
                        r.cancel.cancel();
                        r.archive_cancel.store(true, Ordering::SeqCst);
                    }
                }
            }
        }
        let ids = {
            self.store
                .db
                .lock()
                .unwrap()
                .jobs
                .iter()
                .filter(|j| {
                    id.is_none_or(|id| j.playlist_id.as_deref() == Some(id))
                        && if action == "retry" {
                            j.status == Status::Failed || j.status == Status::Cancelled
                        } else if action == "resume" {
                            matches!(j.status, Status::Paused | Status::Interrupted)
                        } else {
                            !j.status.terminal()
                        }
                })
                .map(|j| j.id.clone())
                .collect::<Vec<_>>()
        };
        for job in ids {
            self.control(app, &job, action).await?;
        }
        let store = self.store.clone();
        let target = id.map(str::to_string);
        let value = to.clone();
        tokio::task::spawn_blocking(move || store.transform_jobs(target.as_deref(), &from, value))
            .await
            .map_err(|e| e.to_string())??;
        {
            let running = self.running.lock().unwrap();
            let mut db = self.store.db.lock().unwrap();
            for b in &mut db.batches {
                if id.is_none_or(|id| b.id == id) && (!b.status.terminal() || action == "retry") {
                    b.status = to.clone();
                    b.error = None;
                    if let Some(r) = running.get(&b.id) {
                        r.cancel.cancel();
                        r.archive_cancel.store(true, Ordering::SeqCst);
                    }
                }
            }
        }
        self.persist().await?;
        self.store.emit(app, "state:changed");
        self.wake();
        Ok(())
    }
    pub async fn control_all(&self, app: &AppHandle, action: &str) -> Result<(), String> {
        self.control_batch(app, None, action).await
    }
    pub fn active(&self) -> bool {
        if !self.running.lock().unwrap().is_empty() {
            return true;
        }
        let db = self.store.db.lock().unwrap();
        db.jobs.iter().any(|j| {
            matches!(
                j.status,
                Status::Queued
                    | Status::RetryWaiting
                    | Status::WaitingForWorker
                    | Status::WaitingForProcessor
                    | Status::DownloadComplete
            )
        }) || db.batches.iter().any(|b| b.status == Status::Queued)
    }
    pub async fn shutdown(&self, app: &AppHandle) {
        self.shutting_down.store(true, Ordering::SeqCst);
        self.stop.cancel();
        {
            let running = self.running.lock().unwrap();
            for r in running.values() {
                r.cancel.cancel();
                r.archive_cancel.store(true, Ordering::SeqCst);
            }
        }
        let _ = self.control_all(app, "pause").await;
        let handles = std::mem::take(&mut *self.handles.lock().unwrap());
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        for mut h in handles {
            if tokio::time::timeout_at(deadline, &mut h).await.is_err() {
                h.abort();
            }
        }
        let _ = self.persist().await;
    }
}
