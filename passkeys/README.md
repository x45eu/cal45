# Cal45 Passkey verifier: isolated integration

`passkeys/` is a **separate Go module** that pins the actively maintained WebAuthn verifier library at v0.18.2. The current Cal45 web server and DAV gateway are not changed by these imports, so incomplete login code is not accidentally exposed.

The module handles:
- Full WebAuthn registration response verification using `FinishRegistration`.
- Full WebAuthn assertion/signature and user-verification checks using `FinishLogin`.
- Exact operator-configured HTTPS DNS origins, no IP addresses.
- Single-use 120-second begin/finish ceremony state, bound to username and independent random browser binding, bounded to 1024 pending operations.
- Interface for durable passkey credential storage with atomic counter/backup flag updates.
- Limits of ten credentials per account.

**Not enabled in the running application.** The `Repository` interface deliberately requires a separate persistence implementation; the current account registry does not store passkeys. Enrollment must be restricted to a previously fully verified Cal45 user or an operator-controlled single-use bootstrap flow. The frontend needs WebAuthn browser API handling, rate limits, and a secure session exchange. The application must never grant a session based only on a ceremony handle.

CI currently resolves module dependencies with `go mod tidy` because a transitive `go.sum` snapshot has not yet been committed. Before deployment pin all resolved checksums, inspect CI results, add integration tests with a browser/hardware authenticator, and perform a security review. The existing Go server remains unchanged and in preview status.
