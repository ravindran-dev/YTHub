# Authentication

Desktop uses Supabase Auth with OS keyring storage through restricted Tauri commands. Browser previews retain sessions in memory only. Android uses AES-GCM with an Android Keystore key and a no-backup encrypted session file. Passwords are never persisted.

Email/password registration, email code verification, recovery codes, password changes, local sign-out and revocation of other refresh sessions are integrated. Configure verification and recovery email templates to display `{{ .Token }}`. No OAuth buttons are exposed because no providers are configured. No URL callback is needed for this code-based flow.

Sessions are validated against the Auth user endpoint. Android may display an offline session only if it was previously validated. Local playback does not require authentication. Account deletion requires explicit confirmation and password reauthentication in the trusted account-files function.

Remote authentication flows have not been verified against a configured project. Configure email rate limits, SMTP, password policy and verification requirements before deployment.
