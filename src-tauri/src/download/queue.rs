#[cfg(test)]
use crate::models::Priority;
use crate::models::{Job, Status};
use std::collections::{HashMap, HashSet};
#[derive(Default)]
pub struct FairQueue {
    served: HashMap<String, u64>,
    waiting: HashMap<String, u64>,
    clock: u64,
}
impl FairQueue {
    pub fn choose(
        &mut self,
        jobs: &[Job],
        busy: &HashSet<String>,
        time: i64,
        mut eligible: impl FnMut(&Job) -> bool,
    ) -> Option<String> {
        if self.served.len() > 1024 {
            self.served.retain(|key, _| {
                jobs.iter()
                    .any(|j| j.playlist_id.as_deref().unwrap_or(&j.id) == key)
            });
        }
        let candidates = jobs
            .iter()
            .filter(|j| {
                !busy.contains(&j.id)
                    && matches!(
                        j.status,
                        Status::Queued
                            | Status::WaitingForWorker
                            | Status::RetryWaiting
                            | Status::DownloadComplete
                            | Status::WaitingForProcessor
                    )
                    && j.retry_at.is_none_or(|at| at <= time)
                    && eligible(j)
            })
            .collect::<Vec<_>>();
        // The same policy serves both phases; an empty processing pass must
        // not erase download aging accumulated across scheduler wakeups.
        self.waiting.retain(|id, _| {
            jobs.iter().any(|j| {
                &j.id == id
                    && matches!(
                        j.status,
                        Status::Queued
                            | Status::RetryWaiting
                            | Status::WaitingForWorker
                            | Status::DownloadComplete
                            | Status::WaitingForProcessor
                    )
            })
        });
        for j in &candidates {
            self.waiting.entry(j.id.clone()).or_insert(self.clock);
        }
        let selected = candidates.into_iter().min_by_key(|j| {
            (
                std::cmp::Reverse(
                    (j.priority as u64
                        + self
                            .clock
                            .saturating_sub(*self.waiting.get(&j.id).unwrap_or(&self.clock))
                            / 16)
                        .min(2),
                ),
                *self
                    .served
                    .get(j.playlist_id.as_deref().unwrap_or(&j.id))
                    .unwrap_or(&0),
                j.playlist_index.unwrap_or(0),
            )
        })?;
        let id = selected.id.clone();
        self.waiting.remove(&id);
        self.clock += 1;
        self.served.insert(
            selected.playlist_id.clone().unwrap_or_else(|| id.clone()),
            self.clock,
        );
        Some(id)
    }
}
pub fn identity(url: &str, kind: &str, quality: &str, container: &str) -> String {
    let parsed = url::Url::parse(url).ok();
    let item = parsed
        .as_ref()
        .and_then(|u| {
            u.query_pairs()
                .find(|(k, _)| k == "v")
                .map(|(_, v)| v.into_owned())
                .or_else(|| {
                    u.path_segments().and_then(|mut s| {
                        if u.host_str() == Some("youtu.be") {
                            s.next().map(str::to_string)
                        } else {
                            let first = s.next()?;
                            ["shorts", "embed", "live"]
                                .contains(&first)
                                .then(|| s.next().unwrap_or("").to_string())
                        }
                    })
                })
        })
        .unwrap_or_else(|| url.to_string());
    format!("youtube:{item}:{kind}:{quality}:{container}")
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::sample_job;
    #[test]
    fn low_priority_ages_under_sustained_high_priority() {
        let mut jobs = vec![];
        let mut low = sample_job();
        low.id = "low".into();
        low.priority = Priority::Low;
        jobs.push(low);
        for i in 0..100 {
            let mut j = sample_job();
            j.id = format!("high-{i}");
            j.priority = Priority::High;
            jobs.push(j);
        }
        let mut q = FairQueue::default();
        let mut busy = HashSet::new();
        let mut found = false;
        for _ in 0..34 {
            assert!(q.choose(&jobs, &busy, 0, |_| false).is_none());
            let id = q.choose(&jobs, &busy, 0, |_| true).unwrap();
            found |= id == "low";
            busy.insert(id);
        }
        assert!(found, "Low priority must age rather than starve");
    }
    #[test]
    fn fairness_priority_and_order() {
        let mut jobs = vec![];
        for (group, n) in [("A", 1000), ("B", 20)] {
            for i in 1..=n {
                let mut j = sample_job();
                j.id = format!("{group}{i}");
                j.playlist_id = Some(group.into());
                j.playlist_index = Some(i);
                jobs.push(j);
            }
        }
        let mut q = FairQueue::default();
        let mut busy = HashSet::new();
        let mut picked = vec![];
        for _ in 0..4 {
            let id = q.choose(&jobs, &busy, 0, |_| true).unwrap();
            busy.insert(id.clone());
            picked.push(id);
        }
        assert_eq!(picked, vec!["A1", "B1", "A2", "B2"]);
        jobs[0].priority = Priority::High;
        busy.clear();
        assert_eq!(q.choose(&jobs, &busy, 0, |_| true).unwrap(), "A1");
    }
    #[test]
    fn equivalent_links_deduplicate() {
        assert_eq!(
            identity("https://youtu.be/abc?t=5", "audio", "320", "mp4"),
            identity(
                "https://www.youtube.com/watch?v=abc&list=x",
                "audio",
                "320",
                "mp4"
            )
        );
    }
}
