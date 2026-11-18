# Cal45 web frontend

This is the **first real, lightweight Go UI scaffold** for Cal45. It is a design preview, not a production user application.

## Design

The UI uses the same palette as Media45:
- `#60A5FA` blue
- `#D4DBE5` text
- `#19222C` panels

No npm, Node.js, external JavaScript, CDN, or UI framework is required. All current pages are rendered server-side using Go standard-library templates and local CSS.

## Local development

Requires a supported Go toolchain:

```sh
go run ./cmd/cal45-web
```

Open http://127.0.0.1:8080. The app intentionally listens on loopback by default.

## Security development plan (inspired by Media45)

Media45 currently implements password login throttling, server sessions, TOTP with anti-replay, recovery codes, and WebAuthn passkeys. Cal45 follows those **security requirements**, but does not reuse Django/Python code inside Go.

Before the frontend can be used with real accounts and DAV data, implement and test:

1. Shared identity integration for Cal45 web and Radicale DAV (no parallel user stores, no bypass via raw DAV endpoints).
2. A secure authentication service: password hashing (Argon2id), server-side sessions, secure HttpOnly/SameSite cookies, CSRF protection, login rate limiting, and logout/session invalidation.
3. TOTP with step anti-replay, throttling, encrypted enrollment secrets, one-time recovery codes and safe recovery processes.
4. WebAuthn/passkeys using a maintained, audited Go library; verify origin/RP ID, user verification, challenge binding and expiry, credential counters and uniqueness.
5. TLS reverse proxy, strict host validation, CSP, no exposed DAV ports, tests, dependency scanning, backups, threat review, and protected secrets.

**Important:** Settings page describes these capabilities as *planned*. No sign-in, passkey enrollment or TOTP endpoints exist yet. Never expose this preview as a public production deployment.

The built-in calendar view is placeholder UI and does not load real events.
