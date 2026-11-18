# Cal45

![Cal45 logo](assets/cal45-logo.svg)

**Cal45 – Dein Kalender. Deine Kontakte. Deine Cloud.**

Cal45 is a lightweight, self-hosted CalDAV/CardDAV service with a standalone Go frontend and a separate DAV backend.

## Current status

- Go HTML/CSS frontend: **initial design preview available** (no login, no live calendar sync).
- DAV backend: Radicale, using htpasswd and owner-only rights.
- Passkeys/TOTP: **planned; not implemented yet**. See [Frontend architecture and security roadmap](docs/frontend.md).
- Both ports are bound to loopback by default; **not ready for public production use**.

## Quick start (local only)

```bash
git clone https://github.com/x45eu/cal45.git
cd cal45
mkdir -p data
htpasswd -B -c config/users YOUR_USERNAME
docker compose up -d --build
docker compose logs -f web
```

- Cal45 preview: http://127.0.0.1:8080/
- DAV backend: http://127.0.0.1:5232/ (for compatible clients, on localhost only)

For more users: `htpasswd -B config/users ANOTHER_USERNAME` (do not use `-c` again).

## Project structure

- `cmd/cal45-web/`: server-side Go application
- `cmd/cal45-web/templates/`: accessible HTML templates
- `cmd/cal45-web/static/`: local CSS and brand assets
- `web.Dockerfile`: lightweight multi-stage Go build
- `config/config`: DAV engine authentication, authorization and storage
- `docs/frontend.md`: design and security architecture
- `assets/cal45-logo.svg`: canonical Cal45 logo

## Branding

- Blue `#60A5FA`, light gray `#D4DBE5`, navy `#19222C`.
- User-facing UI says **Cal45** and **Bereitgestellt von Cal45**.
- No trackers, CDNs, npm, or UI framework.
- The standalone DAV engine still has its own default web interface if accessed directly. This will be removed from user-facing routes when a production reverse proxy is introduced.

## Production security requirements

**Do not deploy publicly in its current state.** There is no authenticated Cal45 web user session or account administration yet. WebAuthn/TOTP screens are informational only. Refer to [docs/frontend.md](docs/frontend.md) for concrete security acceptance criteria, including sharing identities with DAV clients.

## Credits and licensing

Cal45 uses [Radicale](https://github.com/Kozea/Radicale) as a separate upstream component. Radicale is GPL-3.0 licensed; preserve notices and meet applicable licensing obligations when redistributing or modifying it.

No license for original Cal45 application code has been selected yet.
