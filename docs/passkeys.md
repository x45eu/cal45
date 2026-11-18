# Cal45 Passkey / FIDO2 integration status

The first server-side WebAuthn ceremony state primitives are now checked in.

- One-time, cryptographically random 256-bit ceremony handles.
- Bound to user, existing web-session token, purpose (registration/login), configured HTTPS origin and DNS relying party.
- 120-second expiry, single-use consumption (including failed binding attempts).
- Maximum 1024 pending ceremonies per process.
- DNS-only relying-party origin validation; HTTP, IP literals and arbitrary paths are forbidden.
- Unit tests cover expiry, replay, cross-user/cross-session binding, origin validation, and bounded state.

**Not yet a functional Passkey login.** A ceremony handle is not a WebAuthn assertion and proves nothing by itself. Before any route can create a session, implement and test cryptographic WebAuthn verification, including challenge, RP ID, origin, user verification, attestation/credential registration, signature and counter/backup-state handling. Use the maintained `github.com/go-webauthn/webauthn` library (v0.18.x candidate as of November 2026); validate its API and lock dependencies and transitive versions when integrating.

Passkey enrollment must require a preauthenticated account and successful reauthentication; there must not be an unauthenticated registration route for an arbitrary username. The existing account CLI is an operator-provisioned allowlist, not authentication.

State is currently in-memory and will be lost on restart; only suitable as an isolated building block. Do not serve it from a public endpoint until session integration, persistent credential storage, MFA policy, rate limiting and reverse proxy security are complete. The app-token management web UI remains disabled.

See also [web authentication roadmap](web-auth.md).


## November 2026 implementation update

The isolated [passkeys Go module](../passkeys/README.md) now uses `go-webauthn/webauthn` v0.18.2 for actual WebAuthn registration and assertion signature verification, with user verification required. It also provides a file-backed account-bound credential store (stable random handles, credential uniqueness, counter updates and account disable checks), but **it has no publicly exposed web routes**.

Next integration gates: (1) strongly authorized initial enrollment and recovery, (2) exact TLS origin and session-binding middleware, (3) browser ceremony handlers and user verification, (4) MFA/session policies, (5) verified self-service device management. The isolated module CI runs `go mod tidy`, `go test -race` and `go vet`; its outcome is not yet confirmed, and dependency checksums still need to be committed.
