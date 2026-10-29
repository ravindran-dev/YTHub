# Security and authorization

Every exposed account table enables RLS with ownership checks based on auth.uid(). Composite foreign keys prevent playlist/favorite references across accounts. Synced entities revoke direct writes and expose an allowlisted version-checked RPC. Ownership and local paths cannot be patched through it. Anonymous access is denied.

Storage insertion requires an owned active reservation. A database trigger serializes insertion with reservation state changes. Only confirmed objects are readable. Confirmation and deletion RPCs are granted only to service_role. Never put that credential in a client build.

The Edge Function verifies the bearer token, independently scopes object queries, and reauthenticates account deletion. Local copies are untouched. Other-session logout revokes refresh sessions; issued access tokens remain valid until their configured expiry.

Run npm test. cloud-authorization.test.ts executes migrations in embedded PostgreSQL with Auth/Storage schema stubs and tests RLS, cross-account references, immutable ownership, stale writes, idempotency, storage reservations and quotas. These tests do not exercise the hosted Auth server, Storage gateway, email delivery or Edge runtime. Two-user live tests remain required before production use.
