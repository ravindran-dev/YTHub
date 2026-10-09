use futures_util::FutureExt;
use std::{future::Future, sync::Arc};
use tokio::{
    sync::{mpsc, Mutex},
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;
pub fn pool<W: Send + 'static, F, Fut>(
    count: usize,
    capacity: usize,
    stop: CancellationToken,
    execute: F,
) -> (mpsc::Sender<W>, Vec<JoinHandle<()>>)
where
    F: Fn(W) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = ()> + Send + 'static,
{
    let (tx, rx) = mpsc::channel::<W>(capacity);
    let rx = Arc::new(Mutex::new(rx));
    let execute = Arc::new(execute);
    let mut handles = Vec::new();
    for _ in 0..count {
        let rx = rx.clone();
        let execute = execute.clone();
        let stop = stop.clone();
        handles.push(tokio::spawn(async move {loop {let work=tokio::select! {biased; _=stop.cancelled()=>break,work=async {rx.lock().await.recv().await}=>work};let Some(work)=work else {break}; let _=std::panic::AssertUnwindSafe(execute(work)).catch_unwind().await;} }));
    }
    (tx, handles)
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    #[tokio::test]
    async fn reusable_pool_is_bounded_for_1000_items() {
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let done = Arc::new(AtomicUsize::new(0));
        let stop = CancellationToken::new();
        let (a, p, d) = (active.clone(), peak.clone(), done.clone());
        let (tx, handles) = pool(4, 8, stop.clone(), move |_: usize| {
            let (a, p, d) = (a.clone(), p.clone(), d.clone());
            async move {
                let n = a.fetch_add(1, Ordering::SeqCst) + 1;
                p.fetch_max(n, Ordering::SeqCst);
                tokio::task::yield_now().await;
                a.fetch_sub(1, Ordering::SeqCst);
                d.fetch_add(1, Ordering::SeqCst);
            }
        });
        for i in 0..1000 {
            tx.send(i).await.unwrap();
        }
        drop(tx);
        for h in handles {
            h.await.unwrap();
        }
        assert_eq!(done.load(Ordering::SeqCst), 1000);
        assert!(peak.load(Ordering::SeqCst) <= 4);
        assert_eq!(active.load(Ordering::SeqCst), 0);
    }
}
