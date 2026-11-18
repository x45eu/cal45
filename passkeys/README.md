# Cal45 Passkey verifier: isolated integration

`passkeys/` is a **separate Go module** that pins the actively maintained WebAuthn verifier library at v0.18.2. The current Cal45 web server and DAV gateway are not changed by these imports, so incomplete login code is not accidentally exposed.

The module handles:
- Full WebAuthn registration response verification using `FinishRegistration`.
- Full WebAuthn assertion/signature and user-verification checks using `FinishLogin`.
- Exact operator-configured HTTPS DNS origins, no IP addresses.
- Single-use 120-second begin/finish ceremony state, bound to username and independent random browser binding, bounded to 1024 pending operations.
- A durable single-host JSON repository implementation: random stable account handles, account allowlist checking, atomic credential/counter updates, and duplicate credential rejection. The repository is not yet wired into Cal45's web handlers.
- Limits of ten credentials per account.

**Not enabled in the running application.** The `Repository` interface now has a `JSONRepository` implementation compatible with Cal45's existing account allowlist. Account handles must be initialized by a strongly authorized flow with `InitializeHandle`, and this operation must not be exposed as a public endpoint. Enrollment must be restricted to a previously fully verified Cal45 user or an operator-controlled single-use bootstrap flow. The frontend needs WebAuthn browser API handling, rate limits, and a secure session exchange. The application must never grant a session based only on a ceremony handle.

CI currently resolves module dependencies with `go mod tidy` because a transitive `go.sum` snapshot has not yet been committed. Before deployment pin all resolved checksums, inspect CI results, add integration tests with a browser/hardware authenticator, and perform a security review. The existing Go server remains unchanged and in preview status.
