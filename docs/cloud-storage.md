# Private media storage

Apply migrations in order and deploy account-files. The music-private bucket is private. Uploads require a quota reservation belonging to an existing owned library entry. The trusted configuration defaults to 1 GiB per account and 25 MiB per file; administrators may update storage_limits. Pending reservations count toward usage until explicitly cleaned up.

Clients validate file size/type and compute SHA-256. The server downloads the uploaded object, checks actual byte count, SHA-256 and basic audio signatures, then atomically confirms availability and links library metadata. Supported formats are MP3, M4A, FLAC, OGG/Opus and WAV. This validation is not a full media decoder.

Downloads verify length and SHA-256 before saving/importing. Android imports into MusicRepository. Desktop initiates a file download using its webview/browser download facility; native desktop save behavior still needs platform verification. Cancellation and retries are available. Interrupted transfers restart from the beginning; resumable TUS uploads are not implemented.

Deleting a cloud copy does not delete local copies. Deletion marks the reservation, removes Storage data, then finalizes metadata. Failed deletions retain their charged reservation for retry. Expired pending reservations need operator cleanup through the same deletion workflow; no cleanup schedule is deployed.
