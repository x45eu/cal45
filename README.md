# Cal45

![Cal45 logo](assets/cal45-logo.svg)

**Cal45 – Dein Kalender. Deine Kontakte. Deine Cloud.**

Cal45 is a lightweight, self-hosted CalDAV/CardDAV service with a standalone Go frontend and a separate DAV backend.

## Current status

- Go HTML/CSS frontend: **initial design preview available** (no login, no live calendar sync). A separately opt-in auth-preview now provides passkey login, sessions and device-token management, but has not yet secured the main calendar web frontend.
- DAV backend: Radicale isolated behind a Go app-password gateway, with owner-only rights.
- Android: Cal45 Sync v0.13 includes CardDAV/CalDAV imports, guarded updates, private sync journal, local-change previews and an **offline, version-checked lossless single-field DAV patch preview**. Actual DAV uploads and deletions remain disconnected from the UI/SyncAdapters until consent, durable reconciliation and device tests exist. See [Android status](android/README.md).
- Passkeys: the optional, separately launched auth-preview now connects WebAuthn verification, persistent credentials, browser login, secure sessions and self-service device passwords. **It is not enabled by default or connected to the main calendar frontend**. TOTP and account recovery policies remain unfinished. See [Auth preview deployment notes](docs/auth-preview.md).
- The UI and DAV gateway bind only to loopback; the DAV engine has no host port. **Not ready for public production use**.

## Quick start (local only)

```bash
git clone https://github.com/x45eu/cal45.git
cd cal45
mkdir -p data
docker compose up -d --build
docker compose logs -f gateway web
```

- Cal45 preview: http://127.0.0.1:8080/
- DAV gateway: http://127.0.0.1:8081/ (local test only; Android requires a public HTTPS DNS name)

Create an active account first: `docker compose exec gateway /usr/local/bin/cal45-web account add alice`.

Then issue its device token: `docker compose exec gateway /usr/local/bin/cal45-web token issue alice "Pixel 9a"`.

Accounts can be disabled without deleting calendars or contacts. See the [Gateway guide](docs/dav-gateway.md) for revocation and re-enabling.

## Project structure

- `cmd/cal45-web/`: server-side Go application
- `cmd/cal45-web/templates/`: accessible HTML templates
- `cmd/cal45-web/static/`: local CSS and brand assets
- `web.Dockerfile`: lightweight multi-stage Go build
- `config/config`: DAV engine trusted-proxy authentication, authorization and storage
- `docs/frontend.md`: design and security architecture
- `docs/web-auth.md`: TOTP, sessions, and WebAuthn integration requirements
- `docs/passkeys.md`: passkey ceremony state and security boundaries
- `passkeys/BOOTSTRAP.md`: operator-issued first-passkey grants
- `passkeys/ENROLLMENT_HTTP.md`: browser enrollment flow
- `docs/auth-preview.md`: optional local passkey web service and TLS deployment caveats
- `passkeys/DEVICES.md`: authenticated device password management
- `passkeys/`: opt-in Passkey auth service with account sessions, operator-issued bootstrap grants and authenticated DAV-device-token UI (not deployed by default)
- `docs/dav-gateway.md`: operator accounts, app tokens, gateway and migration
- `assets/cal45-logo.svg`: canonical Cal45 logo

## Branding

- Blue `#60A5FA`, light gray `#D4DBE5`, navy `#19222C`.
- User-facing UI says **Cal45** and **Bereitgestellt von Cal45**.
- No trackers, CDNs, npm, or UI framework.
- The standalone DAV engine still has its own default web interface if accessed directly. This will be removed from user-facing routes when a production reverse proxy is introduced.

## Production security requirements

**Do not deploy publicly in its current state.** The main Cal45 web UI still has no authenticated session or account administration. A separate opt-in auth-preview supports passkey sessions and device credentials, but requires complete TLS/proxy integration, MFA/recovery policies and security review before public use. WebAuthn/TOTP screens are informational only. Refer to [docs/frontend.md](docs/frontend.md) for concrete security acceptance criteria, including sharing identities with DAV clients.

## Credits and licensing

Cal45 uses [Radicale](https://github.com/Kozea/Radicale) as a separate upstream component. Radicale is GPL-3.0 licensed; preserve notices and meet applicable licensing obligations when redistributing or modifying it.

No license for original Cal45 application code has been selected yet.
