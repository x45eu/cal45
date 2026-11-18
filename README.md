# Cal45

![Cal45 logo](assets/cal45-logo.svg)

**Cal45 – Dein Kalender. Deine Kontakte. Deine Cloud.**

Cal45 is a lightweight, self-hosted CalDAV/CardDAV service with a standalone Go frontend and a separate DAV backend.

## Current status

- Go HTML/CSS frontend: **initial design preview available** (no login, no live calendar sync).
- DAV backend: Radicale isolated behind a Go app-password gateway, with owner-only rights.
- Passkeys: **cryptographic WebAuthn verification and persistent credential storage now implemented in the isolated `passkeys/` Go module**, but this module is not connected to the web server; no usable passkey login is active. TOTP, recovery and web-session primitives exist but remain unconnected. See [Web authentication roadmap](docs/web-auth.md).
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
- `passkeys/`: separate WebAuthn verification module and durable credential store (not yet connected to web login)
- `docs/dav-gateway.md`: operator accounts, app tokens, gateway and migration
- `assets/cal45-logo.svg`: canonical Cal45 logo

## Branding

- Blue `#60A5FA`, light gray `#D4DBE5`, navy `#19222C`.
- User-facing UI says **Cal45** and **Bereitgestellt von Cal45**.
- No trackers, CDNs, npm, or UI framework.
- The standalone DAV engine still has its own default web interface if accessed directly. This will be removed from user-facing routes when a production reverse proxy is introduced.

## Production security requirements

**Do not deploy publicly in its current state.** There is no authenticated Cal45 web user session or account administration yet. Gateway app passwords are operator-provisioned only. WebAuthn/TOTP screens are informational only. Refer to [docs/frontend.md](docs/frontend.md) for concrete security acceptance criteria, including sharing identities with DAV clients.

## Credits and licensing

Cal45 uses [Radicale](https://github.com/Kozea/Radicale) as a separate upstream component. Radicale is GPL-3.0 licensed; preserve notices and meet applicable licensing obligations when redistributing or modifying it.

No license for original Cal45 application code has been selected yet.
