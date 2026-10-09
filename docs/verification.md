# Verification — backend refactor

Recorded 9 October 2026. These results distinguish offline scheduler tests, real local media tests, and external-network testing.

## Automated checks

| Check | Result |
|---|---|
| `cargo fmt -- --check` | Passed |
| `cargo check` | Passed |
| `cargo clippy --all-targets -- -D warnings` | Passed |
| `cargo test` | 40 passed; 3 opt-in tests ignored by default |
| `npm run test` | 32 passed |
| `npm run lint` | Passed |
| `npm run build` | Passed |
| Real offline FFmpeg/FFprobe processing test | Passed separately |
| Real FFmpeg Job Object termination test | Passed separately |
| `npm run tauri build` and portable packaging | Passed; final installer and portable ZIP generated |
| `scripts/verify-artifacts.ps1` | Passed: source freshness, executable equality, ZIP resources and engine hashes |

## Offline load tests

The load harness exercises production bounded pools, semaphores, task transitions, retry classification, real progress coalescing and phase handoff with asynchronous mock work. It does not contact YouTube, create 1,000 external processes, exercise a complete native UI session, or measure real network performance.

| Items | Completed | Permanent failures | Simulated retries | Peak download workers | Peak processing workers |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 0 | 1 | 1 |
| 10 | 10 | 0 | 1 | 3 | 2 |
| 50 | 47 | 3 | 7 | 3 | 2 |
| 100 | 93 | 7 | 13 | 3 | 2 |
| 500 | 462 | 38 | 66 | 3 | 2 |
| 1,000 | 924 | 76 | 132 | 3 | 2 |

These are deterministic expected outcomes from injected failures. Timing varies by machine and concurrent build load; no speedup or real-world playlist throughput is claimed. Additional tests verify fair scheduling between playlists of different sizes, priority aging, canonical URL deduplication, aggregate rate/fragment budgets, disk-reservation release and rejection, independent ZIP workers, and prevention of completion after pause/cancellation.

A 1,000-job SQLite restart test checks that all persisted records remain, only 232 enter the startup window, staged sources survive, unfinished jobs become interrupted, and at most 32 pending jobs are returned for staging. An uncommitted transaction is rolled back. Legacy backup import and writable Unicode paths are tested. A 1,000-entry manifest test confirms bounded 64-entry reads and correct cursor continuation.

Frontend regressions cover waiting, retry, interrupted and merging controls, native output/clipboard commands, paged-history refresh after removal/clearing, and edited settings reaching the native command boundary. History search currently filters the displayed 200-row page.

Real Windows PowerShell fixtures test structured subprocess progress, HTTP-429 error classification, oversized-output rejection, and cancellation of an inherited child process under the production supervisor. These subprocess tests run in the normal offline suite.

## Actual media and archive checks

The opt-in offline media test generates local sine-wave audio, video and cover art, then runs the production FFmpeg processing path and FFprobe validation for MP3 128/192/256/320/best, cover art/title/artist/album, separate-input MP4 and MKV, and corrupt-source rejection. Paths include Tamil text, spaces and brackets. Separate tests read a real streamed ZIP to confirm index order and check that cancellation leaves no published archive. Atomic output replacement preserves an existing file when commit fails.

The real YouTube opt-in test has been changed to use the new separate download/processing pipeline but has not been rerun for this refactor. Prior-release live tests do not validate the new scheduler. Large-playlist network throughput, exhaustive frame decoding, clean-machine installation, all notification/autostart behaviors, and every YouTube authentication/access restriction remain outside the verified results.

## Reproduce

```powershell
cargo fmt --manifest-path src-tauri/Cargo.toml -- --check
cargo check --manifest-path src-tauri/Cargo.toml
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml
cargo test --manifest-path src-tauri/Cargo.toml mock_engine_load -- --nocapture
cargo test --manifest-path src-tauri/Cargo.toml real_offline_audio_video_metadata_and_corrupt_output -- --ignored
cargo test --manifest-path src-tauri/Cargo.toml dropping_job_stops_real_process -- --ignored
npm run test
npm run lint
npm run tauri build
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-portable.ps1
```

The default suite does not require Internet access or downloading additional media tools. Opt-in local-media tests require already-provisioned bundled engines. Local installer builds remain unsigned.

## Final artifacts

- `artifacts/YTHub_1.0.0_x64-setup.exe`: 196,791,217 bytes, SHA256 `FF61CA6ADF6DA217C390B6F9519682C8BD67817C0F3B8851DBFEBF24254C4596`.
- `artifacts/YTHub-portable.zip`: 202,030,016 bytes, SHA256 `8AEC5834B9A51AE57D5A0937E0E121F7FB4E0339D00A36622BD7D13B9BCF74AA`.
- The portable executable matches the final NSIS-patched release executable. All four bundled engine executable hashes match the source resources. Checksums are also saved in `artifacts/checksums.json`.
