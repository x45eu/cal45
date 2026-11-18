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


## Browser integration progress

An opt-in HTTP adapter and Cal45-branded HTML/CSS/vanilla-JavaScript passkey login screen now exist under `passkeys/`. The browser uses the native WebAuthn API; no third-party browser JavaScript is required. The server's registration routes remain absent unless an embedding application explicitly supplies a strongly reauthenticated account authorization callback. Login routes remain absent without a session issuer.

The separate module's dependency graph and Go checksums are pinned. **Still not integrated with Cal45's publicly started web service**; a real bootstrap mechanism, full user-session policy, rate limiting, browser/hardware tests and a passing production build must precede launch. Do not expose these as working login routes yet.


## Initial passkey setup grants

Operator-created enrollment grants now have an independent CLI, a 10-minute expiry, SHA-256-only storage and atomic single-use consumption. Details: [passkeys/BOOTSTRAP.md](../passkeys/BOOTSTRAP.md). The passkey enrollment HTTP integration remains intentionally disabled until a secure bootstrap-session handshake, browser ceremony, rate limits and end-to-end tests are implemented.


## Additional pre-launch protections

Both opt-in passkey HTTP adapters now include bounded source and global attempt limits; trusted reverse-proxy rate limits remain necessary. The initial-registration repository operation atomically rejects second credentials even under concurrent requests, and counters cannot revert to zero after becoming positive. Tests for these cases have been added. Full build/test success has not yet been independently confirmed.
