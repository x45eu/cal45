# Passkey initial enrollment: operator bootstrap grants

Initial enrollment is protected by a **server-operator-issued 256-bit, one-time setup grant**. Grants last 10 minutes, are stored as SHA-256 digests and can be revoked. The recipient's account must exist and be active in Cal45's existing account registry. Issuing a new grant invalidates earlier grants for the same user. Atomic file locking ensures two concurrent consumers cannot redeem the same token.

## Setup-grant CLI

This separate Go module provides an operator-only helper. From a trusted server with access to the protected Cal45 auth volume, compile:

```sh
cd passkeys
go build -o cal45-passkey-bootstrap ./cmd/cal45-passkey-bootstrap
```

On the server, configure `CAL45_TOKEN_FILE` and `CAL45_BOOTSTRAP_FILE` paths, then run:

```sh
./cal45-passkey-bootstrap issue alice
./cal45-passkey-bootstrap revoke alice
```

The one-time setup secret is printed once to stdout. Do not put the token in GitHub logs, a URL or a browser history entry. Store the private `bootstrap.json` on protected durable storage.

**Not yet integrated into the running Cal45 server.** This is a secure grant issuing/consumption building block, not a functioning browser enrollment path. A finished browser route must (1) validate and consume the token once, (2) bind subsequent WebAuthn registration steps to a Secure/HttpOnly browser cookie and one-time ceremony, (3) verify the WebAuthn attestation/signature and user-verification policy, (4) persist the credential and (5) never create a session based on the setup token alone. Server-side rate limiting and account-recovery controls remain required. Never expose this CLI as an HTTP endpoint.

The existing Cal45 web preview remains unchanged.
