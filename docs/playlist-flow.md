# Playlist flow

Analyze displays up to 100 metadata preview items. Enqueue discovers up to 10,000 full playlist entries into a compact JSONL manifest. The Rust scheduler materializes a bounded staging window, downloads raw sources with bounded yt-dlp processes, and sends successful source records to independent FFmpeg processing workers.

Each item retains its original playlist index for filenames and archive ordering. Progress and counters include durable items outside the current UI window. Playlist-wide pause, resume, cancel and retry commands affect both visible jobs and off-window persisted jobs. Individual failures do not stop other entries.

After all entries reach terminal states, an independent ZIP worker streams validated successful files in index order. Incomplete archives require an explicit preference and are labeled incomplete. See [download-engine.md](download-engine.md) and [recovery.md](recovery.md).
