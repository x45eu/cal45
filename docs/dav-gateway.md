# Cal45 DAV gateway – initial security implementation

The gateway is a Go reverse proxy that verifies device-specific Cal45 app passwords and forwards a verified identity to Radicale. The backend uses `http_x_remote_user`, which is **unsafe to expose directly**. Docker isolates Radicale on an internal network with no host port. Do not attach that container to untrusted networks.

## Operator provisioning (initial version, no user-facing UI)

Build and start locally:
```sh
docker compose up -d --build
```

Create the Cal45 account first (operator-only command, without a password):

```sh
docker compose exec gateway /usr/local/bin/cal45-web account add alice
```

Only active Cal45 accounts can receive or use device tokens. The identity registry is stored in the protected `cal45_auth` volume. Account names must match existing DAV collection owners.

Create a token for the active account:
```sh
docker compose exec gateway /usr/local/bin/cal45-web token issue alice "Pixel 9a"
```
The generated `cal45_...` app password is printed only once. It is stored only as a SHA-256 digest (suitable for randomly generated high-entropy tokens, **not** human passwords). Record the token ID for revocation.

```sh
docker compose exec gateway /usr/local/bin/cal45-web token list
docker compose exec gateway /usr/local/bin/cal45-web token revoke TOKEN_ID
```

Disable a user and instantly block **all of their existing app tokens** (without deleting their calendar/contact data):

```sh
docker compose exec gateway /usr/local/bin/cal45-web account disable alice
docker compose exec gateway /usr/local/bin/cal45-web account enable alice
docker compose exec gateway /usr/local/bin/cal45-web account list
```

Re-enabling an account also re-enables its previously non-revoked tokens. To permanently invalidate a device credential, revoke that token individually.

Port 8081 is bound to loopback. External devices require an HTTPS **domain** routed by your trusted TLS reverse proxy to localhost:8081. Never publicly publish raw port 8081, and never expose backend port 5232. The Android client requires HTTPS domains, refuses literal IP addresses, and verifies ordinary TLS certificates.

## Migration and limitations

**Breaking change:** the previous direct port 5232 and htpasswd authentication are disabled. Back up the data volume first. Existing DAV clients require new app tokens and the gateway URL. Keep usernames identical to existing collection owners.

The current Go web UI is still a preview, with **no sign-in or user self-service token issuance**. The account registry is only an operator-managed identity allowlist, not a full browser authentication system. Tokens are provisioned only by a trusted Docker operator. The gateway does not accept account passwords. Passwordless web login, TOTP, recovery, rate limiting, audit logging and an HTTPS deployment must be implemented and tested before production. Direct access from another privileged container on the Docker host still remains a trust boundary. No live integration test with Radicale has yet been performed.
