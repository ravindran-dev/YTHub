# Cloud architecture

The existing React/Tauri 2 desktop downloader and native Android Compose/Media3 application retain their download engines and local persistence. Desktop currently provides a downloader, not an existing local music player. Android integrates account metadata into its existing MusicRepository. Cloud-only tracks use unavailable placeholders until a verified file download is imported.

Supabase Auth supplies immutable identities. PostgreSQL stores profiles, devices, library metadata, playlists, playlist items, favorites, history and preferences. Private Storage holds explicitly selected audio. The account-files Edge Function confirms file integrity and performs privileged deletion. Service credentials belong exclusively in the backend runtime.

Clients poll metadata every 15 seconds while synchronization is enabled. This is a refresh mechanism, not a realtime websocket implementation. Durable account-scoped queues submit version-checked, idempotent per-entity operations. Local playback never waits for cloud requests.

Current limits: remote deployment is unverified; device registration/session listing and history capture are not connected; transfers retry whole files rather than resuming; no remote streaming is exposed. The implementation is not yet production-validated. See development-setup.md for verification gates.
