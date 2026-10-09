# Architecture

React/TypeScript renders native Tauri state and compact progress patches through Zustand. It has no generic shell or filesystem permission and cannot start downloads in browser preview. `main.rs` is the explicit native command boundary, including bounded history paging, diagnostics and playlist-wide controls.

The Rust manager coordinates reusable download, processing and ZIP pools with bounded channels, semaphores, domain transitions, cancellation tokens and a short-lived running-process table. Playlist metadata is streamed to compact disk manifests, while only a bounded window becomes full jobs. SQLite is the durable queue and history; completed items leave the live window without being lost.

Windows Job Objects own subprocess trees. Native shell associations open recorded outputs; the clipboard plugin copies URLs. All media operations use subprocess argument arrays, validated paths, canonical output checks and atomic final-file publication.

Read [download-engine.md](download-engine.md) for the pipeline and modules, [concurrency.md](concurrency.md) for budgets, [reliability.md](reliability.md) for retries/cancellation, and [recovery.md](recovery.md) for storage. The previous implementation is recorded in [backend-audit.md](backend-audit.md).
