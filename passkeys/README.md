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
