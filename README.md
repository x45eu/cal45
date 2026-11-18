# Cal45

![Cal45 logo](assets/cal45-logo.svg)

**Cal45** – Dein Kalender. Deine Kontakte. Deine Cloud.

Cal45 is a self-hosted CalDAV/CardDAV service.

**Status:** v0.1 foundation. The repository currently contains the DAV engine and its configuration, but **not yet a custom calendar or contacts frontend**.

## Branding

All **user-facing Cal45 UI** should use the Cal45 name and visual identity. Approved example copy:

- Application title: **Cal45**
- Service description: **CalDAV / CardDAV**
- Footer: **Bereitgestellt von Cal45**
- No backend product name or attribution footer in normal user-facing navigation or screens.

Brand palette (shared with Media45): `#60A5FA` (blue), `#D4DBE5` (light), `#19222C` (dark).
Logo: [assets/cal45-logo.svg](assets/cal45-logo.svg).

**Note:** The image mockup is not an implemented frontend. The current container still serves the upstream DAV engine's default web UI, which may contain its own branding. Replacing that requires implementing and serving our custom Cal45 frontend.

## Requirements

- Docker Engine with Docker Compose plugin
- `htpasswd` (often installed via `apache2-utils`)

## First start

```bash
git clone https://github.com/x45eu/cal45.git
cd cal45
mkdir -p data
htpasswd -B -c config/users YOUR_USERNAME
docker compose up -d
docker compose logs -f cal45
```

For additional accounts (without `-c`, which would overwrite the file):

```bash
htpasswd -B config/users ANOTHER_USERNAME
```

The DAV endpoint is available locally at **http://127.0.0.1:5232/**. Use your username and password in a compatible CalDAV/CardDAV client. The underlying DAV engine's built-in web interface supports creating calendars and address books; a branded Cal45 client is still planned.

## Security

- The Compose port is bound to loopback only. **Do not expose raw HTTP/5232 to the public Internet.**
- To access Cal45 remotely, put it behind an HTTPS reverse proxy with appropriate forwarding headers and authentication passthrough. Keep the DAV backend inaccessible except from the proxy.
- Password hashes live in `config/users` and are excluded from git. Never commit credentials or user data.
- `owner_only` rights isolate users' collections. Review authorization carefully before implementing sharing.
- Back up `data/` and securely back up account configuration. Test restoration.
- The `stable` image tag follows upstream releases. Pin and test a specific version/digest for production.

### Existing deployments

The Docker Compose service was renamed from `radicale` to `cal45`. On an existing deployment, `docker compose up -d` will replace the old service container; the bind-mounted `data/` directory stays unchanged. **Back up data before upgrading** and check the new service logs via `docker compose logs cal45`.

## Planned work

1. HTTPS reverse proxy / deployment guide
2. Branded Cal45 frontend for calendars, contacts, and settings
3. Optional account administration without bypassing the underlying auth/rights model
4. Automated tests, backup/restore, client compatibility checks

## Credits and licensing (technical/legal attribution)

Cal45 uses [Radicale](https://github.com/Kozea/Radicale) as its external DAV server component. Radicale is open-source software licensed under GPL-3.0. Respect its license, notices, and attribution obligations if redistributing it or modified versions.

No custom Cal45 application code license has been selected yet.
