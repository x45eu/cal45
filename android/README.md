# Cal45 Sync — Android

Cal45 Sync is a native Kotlin Android project for Cal45, intended to work with GrapheneOS without Google Play Services.

## Current development status: 0.8 prototype

- Native branded setup screen.
- Android AccountManager integration: Cal45 appears as a system account after successful HTTPS DAV authentication. The authenticator never shares DAV app passwords via Android auth tokens. Removing the account in the Cal45 UI clears both Android account and encrypted local credentials.
- HTTPS-only **authenticated DAV PROPFIND** check using DNS hostnames, not literal IPv4/IPv6 addresses; single-label names and localhost are rejected. HTTP 207 indicates the DAV resource responded.
- Authentication failures are reported without leaking the password.
- Credentials are saved **only after a successful test**; passwords encrypted via Android Keystore AES-GCM, with ciphertext and nonce in private app preferences and Android backups disabled.
- An explicit button deletes stored local credentials.
- No redirect following, no custom trust-all TLS handling, no external JavaScript or analytics. OkHttp is the one HTTP dependency needed for WebDAV's PROPFIND method.
- **First CardDAV contact import:** a real Android ContactsProvider SyncAdapter now discovers CardDAV address books under the authenticated user's Radicale collection and imports **missing** vCards into an Android RawContacts account. Import is started manually from the app after granting Android READ_CONTACTS/WRITE_CONTACTS permissions. No Google Play Services required.
- **Read-only, import-only:** Existing contacts are not modified, deleted or uploaded. Remote changes, duplicates with pre-existing personal contacts, groups, photos, advanced vCard fields and conflict handling are not synchronized yet.
- **First CalDAV calendar import:** an Android CalendarProvider SyncAdapter now discovers calendars in the authenticated Cal45 account, imports standalone VEVENT records and creates corresponding local read-only calendars. Manual launch requires Android READ_CALENDAR and WRITE_CALENDAR permissions. No event uploads, modifications or deletions.
- **Optional background imports:** Android's built-in periodic sync scheduler can now request import-only contacts and calendars roughly every 6 hours. Enable or disable both explicitly from Cal45 Sync, after granting Contacts and Calendar permissions. Account re-enrollment preserves the selected state. Android battery policy, sync settings and network availability determine the actual schedule; no exact execution time is guaranteed.\n- **Guarded CalDAV updates (v0.7):** Newly imported calendar events retain a strong server ETag and a SHA-256 snapshot of their imported fields. If the ETag changes, the app conditionally downloads the new remote version, then updates only a clean, unmodified local event. Legacy events without metadata, locally edited records, changed or deleted provider entries, and unsupported recurring records are left unchanged and counted as conflicts.
- **Guarded CardDAV updates (v0.8):** Newly imported contacts record a strong server ETag plus a SHA-256 fingerprint of imported names, phone numbers and email addresses. A later server change can update only those fields if the contact has not been locally modified or marked dirty/deleted, the fingerprint still matches, the field counts are unchanged and no unsupported local fields exist. A batch validates the old values again; conflicts are counted rather than overwritten. Older records without a fingerprint remain protected.
- **Calendar limitations:** recurring events, exceptions, duration-only events, ambiguous floating times, multiple VEVENT files, complex/custom timezone definitions and organizer/attendee/alarm data are intentionally skipped. The parser accepts explicit UTC times, ISO offsets, valid IANA TZIDs and date-only full-day events. No automated background sync.
- HTTP 207 from the initial credential probe does not by itself prove successful CardDAV discovery or data import.
- The Android UI only accepts Cal45-formatted app tokens beginning with `cal45_` (at least 32 characters). This prevents accidentally entering an ordinary user password, **but does not cryptographically prove that a credential is an app password**.\n- **BLOCKER:** The Go gateway now validates separately issued Cal45 device tokens and removes untrusted identity headers. The optional Cal45 passkey auth-preview has a device-token web UI, but trusted HTTPS deployment and real device compatibility testing are pending.

## No Android Studio needed

Go to the repository's **Actions** tab → **Cal45 Sync Android APK** → download `Cal45-Sync-debug-apk` from a successful run and unzip it. The file inside is `app-debug.apk`.

Debug builds are testing-only. Future installable releases require a durable signing key kept outside the repository.

## Planned milestones

1. Server-side Cal45 account and device-password management.
2. Android AccountManager integration and safe device credentials (account scaffolding implemented; no sync adapters yet).
3. CardDAV discovery and initial vCard → Android RawContacts import (first version implemented; update/delete/conflict handling still outstanding).
4. CalDAV discovery and basic one-time VEVENT import (implemented); recurrence, timezone exceptions and incremental changes outstanding.
5. Android ContactsProvider and CalendarProvider import SyncAdapters (initial versions implemented).
6. Reliable bidirectional sync, recurrence handling, advanced field mapping, permissions/security validation and signed releases. Optional Android-system periodic import exists, but is not yet a complete background synchronization solution.

## Security

The Android Keystore key is local to the installation. Deleting app data or reinstalling clears saved credentials; recover by re-adding the app password. The device-password should be revocable from Cal45's future web UI. The current UI is a development prototype and **not** a production DAV client. Current account storage supports only one Cal45 account per installation; multi-account sync is a later milestone.


## Manually import contacts (development build)

1. Set up your HTTPS Cal45 DAV DNS domain and issue a device-specific `cal45_...` app password in Cal45.
2. In Cal45 Sync, save the domain and app password by running the DAV connection check. This registers the Cal45 system account.
3. Create an address book inside the Cal45 user collection on the Radicale server, if none exists.
4. Tap **„Kontakte aus Cal45 importieren (nur lesend)“** and grant Android contacts permissions. The SyncAdapter requests the address-book list over HTTPS and adds new contacts to Android's contacts provider.

For this first version, enter the **DAV root URL** as the server address (for example, `https://dav.example.org/`), not an individual address book. The discovery looks for CardDAV address books under `/USERNAME/`. Requests have strict size/item limits; larger collections may be rejected until incremental paging is implemented. If a collection has more than 200 **new** contact records, the importer fails rather than silently truncating. Existing Cal45-owned raw contacts are skipped on subsequent imports to avoid replacing local edits.

**Back up your contacts before testing.** This is a preliminary parser supporting names, phone numbers and email addresses. It does not download pictures, categories, birthdays, extended address fields or handle remote updates/deletions. System-contact deletion caused by removing the entire Android account is also possible, so use a test address book first. The APK must still pass CI and be tested on GrapheneOS hardware.


## Manually import a calendar (development build)

1. Set up a CalDAV calendar in the existing Radicale account and use the same HTTPS DAV root URL as for contacts.
2. Verify and save the Cal45 device app password in the Android app; this creates the Cal45 Android system account.
3. Tap **„Termine aus Cal45 importieren (nur lesend)“**, grant the Android calendar permissions, and later check **„Letzten Kalender-Import prüfen“**.
4. New standalone, unambiguous events appear in a Cal45 calendar in Android. Already imported event source URLs with valid change metadata can be updated when the Android event has remained unchanged; older imports are not automatically overwritten.

This is a **test-only import**, not production calendar synchronization. It never deletes or uploads events. Only newly imported events with a strong ETag and an unchanged local snapshot are eligible for guarded updates. It explicitly skips recurring series and complex unsupported records rather than importing an incorrect single instance. Only up to ten calendar collections and 200 new ICS resources may be processed in a run; larger imports fail safely. Back up calendars before trying this with real data. Removing the Android Cal45 system account may remove associated locally imported calendar/contacts data. Server-side data are not touched by the importer.


## Opt-in automatic import (v0.6 developer preview)

After manually testing contacts and calendar imports on a **test account**, tap **„Automatischen Import aktivieren“**. Cal45 Sync checks that the Android system account still exists, the app password is stored, and both Contacts and Calendar permissions were granted. The system is then asked to run **both import-only SyncAdapters approximately every 6 hours**. This cadence is a request, not a guaranteed schedule: GrapheneOS/Android may defer sync for background restrictions, battery policy, or lack of connectivity. Android's global account sync must be on.

**„Automatischen Import ausschalten“** removes both periodic schedules, switches off the per-account automatic flags and cancels any queued sync requests. **„Hintergrundimport-Status prüfen“** checks the actual Android scheduling configuration, rather than only a stored preference. Manual imports remain possible with permissions even while automatic imports are off.

*Known limitations:* this only imports previously unseen server contacts and simple non-recurring calendar events. It does not remove, update or upload records. A server-side change to an already imported object is **not reflected** in Android. Avoid using real critical calendars until recurrence, incremental update/conflict handling and device testing are complete. This function uses Android's built-in sync manager, not WorkManager, AlarmManager or Google Play services.


## CalDAV/CardDAV change tracking (v0.7)

When the CalDAV server publishes a **strong ETag**, Cal45 records it for new calendar event imports together with a digest of the event fields it wrote into Android's CalendarProvider. In later import runs, Cal45 compares the server ETag, fetches changed .ics files with HTTP `If-Match`, and updates only if the local provider still reports an unchanged, non-dirty, non-deleted record. If the local event was edited, it is **not overwritten**. If the remote event becomes a recurrence/unsupported file, it is skipped. Local and remote deletion are not reconciled.

For contacts imported with v0.8 or later, Cal45 also stores a digest of the name, phone and email fields. If a new strong ETag is reported, it conditionally downloads the vCard and compares the current Android fields to the stored digest. Only if the raw contact is clean and the fields and counts match the previous snapshot can a single Android provider transaction update the supported fields. Legacy imports without digest, contacts with extra unsupported fields and phone-edited records are left intact; conflicts appear in the status screen.

**Existing installations:** older imports may be missing the original ETag/snapshot. Those records are not retroactively marked safe to overwrite. If your calendar/contact data are important, make backups and test v0.7 with a fresh separate Cal45 account and small sample collections before using it. Continuous background scheduling remains optional and subject to Android system policy.


## Protected contact updates (v0.8 developer preview)

The initial CardDAV parser supports names, basic phone numbers and email addresses. For newly imported contacts, v0.8 stores the server's strong ETag and a one-way digest of exactly those normalized fields in the Android RawContacts metadata. On later runs, modified CardDAV resources are retrieved with `If-Match` and the local RawContacts and Data values are checked against the old metadata. The app only updates if the previous snapshot matches, the contact is not marked dirty/deleted and there are no local unsupported fields. Changing the number of phone numbers or email addresses is treated as a conflict to avoid incorrect field mapping. Concurrent local modifications cause an atomic provider batch to fail without applying a partial update.

**There is still no upload or deletion of contacts.** This is a first-pass safe-update model, not a general-purpose field merge. Local edits, grouped contacts, labels, attachments, photos and older imported contacts may remain unchanged even when their server copy changes. Use test data and keep a backup; actual Pixel 9a/GrapheneOS provider behavior is not yet verified in hardware tests.
