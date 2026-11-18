# Cal45 Sync — Android

Cal45 Sync is a native Kotlin Android project for Cal45, intended to work with GrapheneOS without Google Play Services.

## Current development status: 0.3 prototype

- Native branded setup screen.
- Android AccountManager integration: Cal45 appears as a system account after successful HTTPS DAV authentication. The authenticator never shares DAV app passwords via Android auth tokens. Removing the account in the Cal45 UI clears both Android account and encrypted local credentials.
- HTTPS-only **authenticated DAV PROPFIND** check using DNS hostnames, not literal IPv4/IPv6 addresses; single-label names and localhost are rejected. HTTP 207 indicates the DAV resource responded.
- Authentication failures are reported without leaking the password.
- Credentials are saved **only after a successful test**; passwords encrypted via Android Keystore AES-GCM, with ciphertext and nonce in private app preferences and Android backups disabled.
- An explicit button deletes stored local credentials.
- No redirect following, no custom trust-all TLS handling, no external JavaScript or analytics. OkHttp is the one HTTP dependency needed for WebDAV's PROPFIND method.
- **No synchronization yet**: system account registration is not a ContactsProvider or CalendarProvider sync adapter. An HTTP 207 check alone does not prove complete CalDAV/CardDAV interoperability.
- The Android UI only accepts Cal45-formatted app tokens beginning with `cal45_` (at least 32 characters). This prevents accidentally entering an ordinary user password, **but does not cryptographically prove that a credential is an app password**.\n- **BLOCKER:** The Go gateway now validates separately issued Cal45 device tokens and removes untrusted identity headers. Issuing tokens remains restricted to the operator CLI; secure web self-service and live compatibility testing are still pending.

## No Android Studio needed

Go to the repository's **Actions** tab → **Cal45 Sync Android APK** → download `Cal45-Sync-debug-apk` from a successful run and unzip it. The file inside is `app-debug.apk`.

Debug builds are testing-only. Future installable releases require a durable signing key kept outside the repository.

## Planned milestones

1. Server-side Cal45 account and device-password management.
2. Android AccountManager integration and safe device credentials (account scaffolding implemented; no sync adapters yet).
3. Standards-compliant CardDAV discovery / vCard sync / conflict handling.
4. CalDAV discovery, iCalendar recurrence/timezone processing, incremental sync.
5. Android ContactsProvider and CalendarProvider integration; SyncAdapters.
6. Automatic background sync, permissions, security tests and signed releases.

## Security

The Android Keystore key is local to the installation. Deleting app data or reinstalling clears saved credentials; recover by re-adding the app password. The device-password should be revocable from Cal45's future web UI. The current UI is a development prototype and **not** a production DAV client. Current account storage supports only one Cal45 account per installation; multi-account sync is a later milestone.
