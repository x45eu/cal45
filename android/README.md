# Cal45 Sync — Android

Cal45 Sync is a native Kotlin Android project for Cal45, intended to support GrapheneOS without Google Play Services.

## Current state: 0.1 prototype

- Native, branded setup screen.
- HTTPS connectivity test (does not follow redirects; never sends passwords).
- **Not yet** a CalDAV/CardDAV synchronizer.
- No Android AccountManager, ContactsProvider, CalendarProvider or background sync integration yet.
- Only stores the server address locally, no credentials.

## No Android Studio needed

Open the repository's **Actions** tab → **Cal45 Sync Android APK** → select the latest successful run → download the `Cal45-Sync-debug-apk` artifact and unzip it. Inside is `app-debug.apk`.

The build workflow also runs on updates to `android/`. If Actions is disabled or the build fails, no APK is produced. This is a debug build, *not* a signed public release.

Use only your own GitHub Actions artifacts for testing. Android may ask you to allow APK installations for the browser or file manager. Treat debug builds as testing-only.

## Next development milestones

1. A common Cal45 account/authentication API for web and device credentials.
2. Android `AccountManager` integration with protected device credential storage.
3. Standards-compliant CardDAV collection discovery, vCard reading/writing and conflict handling.
4. Standards-compliant CalDAV discovery, iCalendar recurrence/timezones and incremental synchronization.
5. ContactsProvider + CalendarProvider account-owned records and `SyncAdapter` scheduling.
6. Permission requests, background sync policies compatible with GrapheneOS, comprehensive tests.
7. Release APK signing with privately stored signing keys and reproducible builds.

The Android system cannot gain CalDAV/CardDAV sync support solely from the server: the native app must implement this functionality. No system contacts or calendars are modified by the prototype.
