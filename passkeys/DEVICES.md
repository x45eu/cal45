# Cal45 device passwords: passkey-authenticated self-service (auth-preview only)

The standalone Cal45 auth service now supports **authenticated device-token management**. Only a user with a valid WebAuthn-authenticated web session may list their own devices. Token creation and revocation require:
- A verified, currently active Cal45 account, scoped by the server-side session.
- Explicit same-origin HTTPS POST request and correct session-specific `X-Cal45-CSRF` token.
- A *fresh* passkey authentication from the previous five minutes. If it has expired, log in again using the passkey.
- Source/global attempt limits.

Endpoints:
- `GET /auth/devices` returns the logged-in user's metadata; **never returns app passwords**.
- `POST /auth/devices/create` with `{"label":"Pixel 9a"}` issues 32 cryptographically random bytes as a `cal45_...` password, returned **once** (HTTP 201). Only the SHA-256 digest, owner, ID and metadata are stored.
- `POST /auth/devices/revoke` with `{"id":"..."}` deactivates the matching owned token (HTTP 204). Tokens belonging to another account are inaccessible.

The code edits the **same Cal45 tokens.json store** used by the Go DAV gateway and CLI, honoring the same POSIX `.lock` file. Writes use a private temporary file, file mode 0600 and atomic rename. Only active accounts may modify tokens; maximum 20 active tokens per account.

These endpoints are currently **inside the optional auth-preview service**, which is loopback-only and not a production identity provider. Before production: complete proxy/HTTPS security, real browser tests, session hardening, TOTP/recovery policy, backup/rotation review, UI testing and cross-process integration tests with live Radicale.

Account passwords are never used on this path. Device credentials remain independently revocable and are only sent to DAV over HTTPS.
