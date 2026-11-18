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

The module has a pinned Go dependency graph and checksum file based on the verified v0.18.2 upstream module. CI uses read-only dependency resolution. Before deployment inspect CI results, add integration tests with a browser/hardware authenticator, and perform a security review. The existing Go server remains unchanged and in preview status.


## Browser login adapter (not enabled)

The optional `Endpoint` in `http.go` exposes POST `/auth/passkey/login/begin` and POST `/auth/passkey/login/finish` only if the embedding server explicitly calls `Register` with a verified session issuer. The begin call takes JSON `{"username":"alice"}` and returns WebAuthn assertion options plus an opaque ceremony handle. It sets a Secure, HttpOnly, SameSite=Strict, 120-second challenge-binding cookie. The finish endpoint receives the raw WebAuthn assertion JSON body and `X-Cal45-Ceremony`/`X-Cal45-Username` headers; it does not issue a session until the WebAuthn verifier returns success and the embedding application's MFA policy callback allows it. Strict Origin checks and a body cap apply. This is an **internal adapter**, not production-ready public authentication: rate limiting, browser UX, initial key enrollment, persistence review, CI success and deployment TLS are outstanding.

The browser login screen is in [web/](web/). The opt-in HTTP adapter requires a verified login session issuer and allows registration routes only when an explicit step-up authorization callback is provided. These are library integration hooks, not configured routes in the running server.


## Initial passkey provisioning

The operator-only helper in [`cmd/cal45-passkey-bootstrap`](cmd/cal45-passkey-bootstrap) generates 10-minute, one-use enrollment grants for previously provisioned active Cal45 accounts. Grant storage uses file locking and never persists plaintext tokens. See [BOOTSTRAP.md](BOOTSTRAP.md). The browser registration route is **not yet wired to this grant**. No public enrollment has been enabled.


## First-passkey enrollment integration

The isolated module now includes opt-in first-enrollment handlers and a Cal45-branded browser screen. An operator-generated single-use ten-minute setup secret is redeemed via an origin-checked JSON POST, followed by a 120-second cookie-bound WebAuthn registration ceremony. The adapter does not mint a login session, and cannot be used to add a second passkey to an enrolled account. **The running preview does not register these endpoints or serve the enrollment page.** See [ENROLLMENT_HTTP.md](ENROLLMENT_HTTP.md) for outstanding deployment security requirements.


## Hardening progress

- Opt-in passkey login and enrollment handlers now have a bounded, in-process source-IP limiter and a global request limit. They do **not** trust arbitrary `X-Forwarded-For` headers. Behind a reverse proxy, all visitors may share a single gateway-IP bucket, so deploy a properly configured trusted proxy with its own per-client limits and a controlled client-IP policy.
- First-passkey registration uses an **atomic first-credential-only** repository operation: parallel initial setup requests cannot add a second credential. Additional passkeys still require reauthenticated step-up authorization.
- Credential counters reject repeated, decreasing, or zero-reset counters once the authenticator had a positive count. Authenticators that consistently report zero remain supported.
- CI now verifies module checksums, runs race-enabled tests, vet, bootstrap CLI compilation, and JavaScript syntax checks.

All endpoint wiring remains opt-in and disabled in the running Cal45 web preview. These controls are not a substitute for deployment TLS/proxy protections, a full MFA policy, and browser/hardware tests.
