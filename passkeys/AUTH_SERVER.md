# Cal45 passkey authentication service (opt-in)

The `passkeys/cmd/cal45-auth-server` command composes the existing verified WebAuthn registration/login flows with actual opaque web sessions and a Cal45-branded browser UI.

## Local trusted deployment

This service **does not replace the existing Go web preview yet**. It is deliberately a separate process and is not included in the default Docker Compose startup.

```sh
cd passkeys
go build -o cal45-auth-server ./cmd/cal45-auth-server
CAL45_AUTH_ORIGIN=https://cal.example.org ./cal45-auth-server
```

The default bind is `127.0.0.1:8082` (HTTP on loopback **only**). The HTTPS origin must be configured explicitly and should be routed by a correctly configured trusted HTTPS proxy to paths beginning with `/auth/`.

The auth process expects the operator-provisioned Cal45 account registry at `CAL45_TOKEN_FILE` (default `/var/lib/cal45/tokens.json`) and needs access to the same protected user allowlist as the DAV gateway. It saves passkeys to `CAL45_PASSKEY_FILE` and bootstrap grants to `CAL45_BOOTSTRAP_FILE`.

Paths: `GET /auth/enroll` for first passkey setup; `GET /auth/login` for passkey login; `GET /auth/account` for the logged-in session overview; `GET /auth/session` for session status; `POST /auth/logout` for revocation. Creation and login POST requests are registered only within this dedicated opt-in service.

Sessions are issued only after full WebAuthn library validation and active-account checks. Setup grants never issue sessions. A request lacking browser Origin or from another site is rejected for authentication actions.

### Restrictions before public production deployment

- The authenticated session in this process is not yet recognized by the **existing Cal45 calendar/contact web frontend**.
- The account page now issues and revokes per-device DAV app passwords only after a fresh WebAuthn login, with CSRF and exact-origin checks. This remains a developer preview, not a production-ready identity or recovery system.
- Sessions and WebAuthn ceremony state are in-memory; restarts invalidate sessions. Single-replica only.
- TLS must terminate at a trusted proxy, and auth headers must not be forgeable; the service itself only speaks plain HTTP on loopback.
- Reverse proxy needs connection limits, abuse controls and strongly isolated DAV storage. HTTP rate limiter is an additional defensive mechanism, not a full production solution.
- Full end-to-end browser/hardware passkey tests, MFA enrollment/recovery and a deployment security review are outstanding.
- Do not publish the auth service directly to the internet or treat it as a fully audited identity provider.

The browser frontend has no external JS libraries, tracking, Google dependencies or CDN links.
