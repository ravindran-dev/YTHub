//! Offline load harness using the production pools, permits, transitions and retry policy.
use super::*;
use crate::{
    errors::ErrorKind,
    models::{sample_job, Status},
};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

#[tokio::test]
async fn mock_engine_load_1_10_50_100_500_1000() {
    for count in [1, 10, 50, 100, 500, 1000] {
        let start = std::time::Instant::now();
        let stop = CancellationToken::new();
        let host = Arc::new(Semaphore::new(3));
        let fragments = Arc::new(Semaphore::new(16));
        let downloads = Arc::new(AtomicUsize::new(0));
        let processors = Arc::new(AtomicUsize::new(0));
        let download_peak = Arc::new(AtomicUsize::new(0));
        let processor_peak = Arc::new(AtomicUsize::new(0));
        let successes = Arc::new(AtomicUsize::new(0));
        let failures = Arc::new(AtomicUsize::new(0));
        let retried = Arc::new(AtomicUsize::new(0));
        let completed = Arc::new(std::sync::Mutex::new(Vec::new()));
        let (p, peak, done, order) = (
            processors.clone(),
            processor_peak.clone(),
            successes.clone(),
            completed.clone(),
        );
        let (processing, ph) =
            worker::pool(2, 4, stop.clone(), move |(id, status): (usize, Status)| {
                let (p, peak, done, order) = (p.clone(), peak.clone(), done.clone(), order.clone());
                async move {
                    let n = p.fetch_add(1, Ordering::SeqCst) + 1;
                    peak.fetch_max(n, Ordering::SeqCst);
                    let status = status
                        .advance(Status::WaitingForProcessor)
                        .unwrap()
                        .advance(Status::Converting)
                        .unwrap();
                    tokio::task::yield_now().await;
                    assert_eq!(
                        status.advance(Status::Completed).unwrap(),
                        Status::Completed
                    );
                    order.lock().unwrap().push(id);
                    done.fetch_add(1, Ordering::SeqCst);
                    p.fetch_sub(1, Ordering::SeqCst);
                }
            });
        let (h, f, a, peak, failed, retries) = (
            host.clone(),
            fragments.clone(),
            downloads.clone(),
            download_peak.clone(),
            failures.clone(),
            retried.clone(),
        );
        let (tx, dh) = worker::pool(6, 8, stop.clone(), move |id: usize| {
            let (h, f, a, peak, failed, retries, processing) = (
                h.clone(),
                f.clone(),
                a.clone(),
                peak.clone(),
                failed.clone(),
                retries.clone(),
                processing.clone(),
            );
            async move {
                let _host = h.acquire_owned().await.unwrap();
                let _fragments = f.acquire_many_owned(4).await.unwrap();
                let n = a.fetch_add(1, Ordering::SeqCst) + 1;
                peak.fetch_max(n, Ordering::SeqCst);
                let mut status = Status::Queued
                    .advance(Status::WaitingForWorker)
                    .unwrap()
                    .advance(Status::Downloading)
                    .unwrap();
                if id.is_multiple_of(13) {
                    assert!(retry::delay(ErrorKind::InvalidUrl, 1, 5, 2, 0).is_none());
                    assert_eq!(status.advance(Status::Failed).unwrap(), Status::Failed);
                    failed.fetch_add(1, Ordering::SeqCst);
                    a.fetch_sub(1, Ordering::SeqCst);
                    return;
                }
                if id.is_multiple_of(7) {
                    let delay = retry::delay(ErrorKind::Network, 1, 5, 2, id as u64).unwrap();
                    assert!((2000..=3000).contains(&delay));
                    retries.fetch_add(1, Ordering::SeqCst);
                    status = status
                        .advance(Status::RetryWaiting)
                        .unwrap()
                        .advance(Status::WaitingForWorker)
                        .unwrap()
                        .advance(Status::Downloading)
                        .unwrap();
                }
                let mut meter = progress::ProgressMeter::new(5);
                let now = std::time::Instant::now();
                let mut emitted = 0;
                for tick in 0..100 {
                    let (_, _, _, emit) = meter.update(
                        tick * 100,
                        Some(10000),
                        now + std::time::Duration::from_millis(tick * 10),
                    );
                    emitted += usize::from(emit);
                }
                assert!(emitted <= 6);
                tokio::task::yield_now().await;
                status = status.advance(Status::DownloadComplete).unwrap();
                a.fetch_sub(1, Ordering::SeqCst);
                processing.send((id, status)).await.unwrap();
            }
        });
        for id in 1..=count {
            tx.send(id).await.unwrap();
        }
        drop(tx);
        for handle in dh {
            handle.await.unwrap();
        }
        for handle in ph {
            handle.await.unwrap();
        }
        assert_eq!(
            successes.load(Ordering::SeqCst) + failures.load(Ordering::SeqCst),
            count
        );
        assert_eq!(failures.load(Ordering::SeqCst), count / 13);
        assert!(download_peak.load(Ordering::SeqCst) <= 3);
        assert!(processor_peak.load(Ordering::SeqCst) <= 2);
        assert_eq!(host.available_permits(), 3);
        assert_eq!(fragments.available_permits(), 16);
        assert_eq!(downloads.load(Ordering::SeqCst), 0);
        assert_eq!(processors.load(Ordering::SeqCst), 0);
        completed.lock().unwrap().sort_unstable();
        assert!(completed.lock().unwrap().windows(2).all(|w| w[0] < w[1]));
        println!("mock items={count} completed={} failed={} retries={} peak_download={} peak_processing={} elapsed_ms={}",successes.load(Ordering::SeqCst),failures.load(Ordering::SeqCst),retried.load(Ordering::SeqCst),download_peak.load(Ordering::SeqCst),processor_peak.load(Ordering::SeqCst),start.elapsed().as_millis());
    }
}

#[test]
fn pause_cancel_restart_and_no_terminal_resurrection() {
    let mut j = sample_job();
    j.status = Status::Downloading;
    assert_eq!(j.status.transition("pause", true).unwrap(), Status::Paused);
    assert!(Status::Paused.advance(Status::Completed).is_err());
    assert!(Status::Cancelling.advance(Status::Completed).is_err());
    assert_eq!(
        Status::Cancelling.advance(Status::Cancelled).unwrap(),
        Status::Cancelled
    );
    assert!(Status::Cancelled.advance(Status::Downloading).is_err());
    assert!(Status::Interrupted.transition("resume", true).is_err());
    assert_eq!(
        Status::Interrupted.transition("resume", false).unwrap(),
        Status::Queued
    );
}

#[tokio::test]
async fn archive_pool_does_not_consume_download_workers() {
    let stop = CancellationToken::new();
    let entered = Arc::new(tokio::sync::Notify::new());
    let release = Arc::new(tokio::sync::Notify::new());
    let (e, r) = (entered.clone(), release.clone());
    let (z, zh) = worker::pool(1, 1, stop.clone(), move |_: ()| {
        let (e, r) = (e.clone(), r.clone());
        async move {
            e.notify_one();
            r.notified().await;
        }
    });
    z.send(()).await.unwrap();
    entered.notified().await;
    let completed = Arc::new(AtomicUsize::new(0));
    let d = completed.clone();
    let (tx, dh) = worker::pool(2, 2, stop, move |_: ()| {
        let d = d.clone();
        async move {
            d.fetch_add(1, Ordering::SeqCst);
        }
    });
    for _ in 0..10 {
        tx.send(()).await.unwrap();
    }
    drop(tx);
    for h in dh {
        h.await.unwrap();
    }
    assert_eq!(completed.load(Ordering::SeqCst), 10);
    release.notify_one();
    drop(z);
    for h in zh {
        h.await.unwrap();
    }
}
