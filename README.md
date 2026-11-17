# Cal45

Cal45 is a self-hosted CalDAV/CardDAV service built on [Radicale](https://radicale.org/).

**Status:** v0.1 foundation. This repository currently contains a minimal DAV server, not yet a custom calendar/contact web client or public sign-up system.

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
docker compose logs -f radicale
```

For additional accounts (without `-c`, which would overwrite the file):

```bash
htpasswd -B config/users ANOTHER_USERNAME
```

The DAV endpoint is available locally at **http://127.0.0.1:5232/**. Use your selected username and password in a compatible CalDAV/CardDAV client. Radicale's built-in interface allows creating calendars and address books; Cal45's own UI is not implemented yet.

## Security

- The Compose port is bound to loopback only. **Do not expose raw HTTP/5232 to the public Internet.**
- To access Cal45 remotely, put it behind an HTTPS reverse proxy with appropriate forwarding headers and authentication passthrough. Keep Radicale inaccessible except from the proxy.
- Password hashes live in `config/users` and are excluded from git. Never commit credentials or user data.
- `owner_only` rights isolate users' collections. Review authorization carefully before implementing sharing.
- Use backups of `data/` **and** securely backed-up account configuration. Test restoration.
- The `stable` image tag follows upstream releases. For reproducible production deployments, pin and test a specific version/digest.

## Planned work

1. HTTPS reverse proxy / deployment guide
2. Cal45 frontend for calendars, contacts, and settings
3. Optional account administration service, without bypassing Radicale's auth/rights model
4. Automated tests, backup/restore instructions, client compatibility checks

## Credits and licensing

Cal45 uses Radicale as an external, separately deployed component. Radicale is open-source software licensed under GPL-3.0; respect its license and attribution when distributing modified versions. See [Radicale](https://github.com/Kozea/Radicale).

No custom Cal45 application code license has been selected yet.
