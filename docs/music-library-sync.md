# Library synchronization

Enable synchronization explicitly in Account. Android captures local track metadata and optionally playlists, favorites and player preferences. Paths, artwork files, cookies and media bytes remain local. Cloud file upload is a separate explicit action.

Operations contain an operation ID, account/device IDs, entity ID, expected version, field patch, attempt count and status. A server receipt makes retransmission idempotent. Stale versions produce a conflict requiring the user to choose remote or local edits. Deletions use tombstones; favorites use an active flag. Playlist order uses position followed by stable item ID. No full-table write replaces a library.

Android stores the queue atomically in no-backup files; desktop stores metadata-only outbox/cache in account-scoped local storage. Tokens never enter these stores. Retry uses bounded exponential backoff. Sync status becomes confirmed only after remote writes and reads succeed.

Cloud metadata does not imply playable media. Android imports verified downloads into the existing local library and maps cloud IDs to device IDs. Signing out removes cloud projections while retaining local audio. Cross-device playback never starts automatically.

Known validation gaps include restart/account-switch stress testing, concurrent playlist edits involving several dependent items, and transfer recovery against a live project. History synchronization and per-category desktop preferences remain incomplete.
