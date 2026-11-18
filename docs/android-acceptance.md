# Cal45 Sync – Pixel / GrapheneOS acceptance checklist

**Status:** Manual device test plan, not a claim of completed verification. Automated Kotlin tests and the debug APK build do not prove Android's providers behave identically on a Pixel 9a with GrapheneOS.

## Prerequisites
- Use a **disposable Cal45 account** with an isolated test address book and calendar, behind a real HTTPS DNS hostname with valid TLS.
- Back up contacts and calendar data before installation. Test revocation and restoration with new throwaway data.
- Download the debug APK from a **successful** GitHub Actions run. Debug signing keys may vary between builds; uninstalling could erase associated Android account data.
- Keep the real Cal45 account and important calendars out of this test. Check Android permissions and background synchronization settings.

## Manual test matrix

| Case | Procedure | Required behavior |
| --- | --- | --- |
| Account enrollment | Connect with a dedicated `cal45_...` token | Android shows one Cal45 account; raw token never appears in AccountManager |
| TLS and redirects | Try an invalid certificate, HTTP hostname, and cross-host redirect | All requests refused; no insecure fallback |
| Contacts first import | Create a vCard with name, phone and email | Exactly one Cal45 raw contact appears |
| Contact update | Change only server-side name while keeping a strong ETag | Name updates once; no duplicate raw contact |
| Contact local conflict | Edit the Android phone number, then change the remote name | Local record remains intact; conflict reported |
| Contact extra fields | Add a photo or unrecognized Android contact data field locally | Entire remote update stays blocked |
| Events first import | Add a normal UTC VEVENT and one all-day event | Correct time, date and calendar; no duplicate import |
| Event server update | Change a simple remote event with a new strong ETag | Unmodified local event updates; locally edited event stays intact |
| Daily recurrence | UTC VEVENT with `FREQ=DAILY;COUNT=5` | Five correct occurrences; master uses RRULE and DURATION |
| Weekly recurrence | UTC event with `FREQ=WEEKLY;COUNT=8;BYDAY=MO,FR` | Correct weekdays and occurrence count |
| Monthly recurrence | All-day event with `BYMONTHDAY=15;COUNT=6` | Correct all-day instances, no timezone date drift |
| Unsupported recurrence | Multiple VEVENTs, EXDATE, RECURRENCE-ID or unlimited RRULE | Entire resource skipped; no single stray occurrence |
| DST boundary | Try a local-TZID recurring event around Europe/Berlin transition | Rejected rather than importing incorrect times |
| Auto-sync disabled | Restart device and leave toggle off | No scheduled automatic sync |
| Auto-sync enabled | Grant all permissions and toggle on | Both Android sync authorities are configured; execution may be deferred |
| Repetition | Run imports repeatedly | No duplicate records |
| Token revocation | Revoke the app password from Cal45 | Subsequent DAV import fails without leaking token |
| Account removal | Remove disposable Cal45 account | Only disposable account-owned records removed; no server-side deletes |

## Release gates

Before recommending Cal45 Sync for real data: confirm this entire matrix on physical GrapheneOS hardware, add emulator/instrumentation provider tests, support recurring exceptions and safe deletion rules, verify the end-to-end CalDAV/CardDAV gateway, and ship a reproducible signed release with documented upgrade/backups. The app does **not** yet implement bidirectional DAV synchronization or server-side uploads.
