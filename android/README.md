# Cal45 Sync — Android

Cal45 Sync is a native Kotlin Android project for Cal45, intended to work with GrapheneOS without Google Play Services.

## Current development status: 0.2 prototype

- Native branded setup screen.
- HTTPS-only **authenticated DAV PROPFIND** check (HTTP 207 indicates the DAV resource responded).
- Authentication failures are reported without leaking the password.
- Credentials are saved **only after a successful test**; passwords encrypted via Android Keystore AES-GCM, with ciphertext and nonce in private app preferences and Android backups disabled.
- An explicit button deletes stored local credentials.
- No redirect following, no custom trust-all TLS handling, no external JavaScript or analytics. OkHttp is the one HTTP dependency needed for WebDAV's PROPFIND method.
- **No synchronization yet**. An HTTP 207 check alone does not prove complete CalDAV/CardDAV interoperability.
- Until Cal45 creates per-device passwords, the test can technically use the current DAV username/password. For real deployment prefer separate revocable app passwords from the planned account service.

## No Android Studio needed

Go to the repository's **Actions** tab → **Cal45 Sync Android APK** → download `Cal45-Sync-debug-apk` from a successful run and unzip it. The file inside is `app-debug.apk`.

Debug builds are testing-only. Future installable releases require a durable signing key kept outside the repository.

## Planned milestones

1. Server-side Cal45 account and device-password management.
2. Android AccountManager integration and safe device credentials.
3. Standards-compliant CardDAV discovery / vCard sync / conflict handling.
4. CalDAV discovery, iCalendar recurrence/timezone processing, incremental sync.
5. Android ContactsProvider and CalendarProvider integration; SyncAdapters.
6. Automatic background sync, permissions, security tests and signed releases.

## Security

The Android Keystore key is local to the installation. Deleting app data or reinstalling clears saved credentials; recover by re-adding the app password. The device-password should be revocable from Cal45's future web UI. The current UI is a development prototype and **not** a production DAV client.
