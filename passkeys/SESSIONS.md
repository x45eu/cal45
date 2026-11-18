# Cal45 authenticated web sessions (implementation component)

The `passkeys/session.go` module now supports opaque, random 256-bit, server-side web sessions. This is specifically for a future Cal45 HTTPS auth service.

- 8-hour maximum lifetime, bounded number of sessions, voluntary revocation.
- Session ID stored as a SHA-256 lookup key, cookie uses `__Host-cal45_session`, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`.
- Active Cal45 account checked before session issuance and on every authenticated request. Disabling an account immediately blocks its sessions.
- GET `/auth/session` returns authenticated username and a per-session CSRF token with `Cache-Control: no-store`.
- POST `/auth/logout` requires matching HTTPS Origin and `X-Cal45-CSRF`, expires cookie and invalidates the server-side session.
- The session issuer is invoked **only after the go-webauthn signature verifier succeeds**, via the `LoginSessionIssuer` callback supplied by the embedding server. Do not issue a session based merely on an operator setup token or a ceremony ID.

**Integration status:** This is an opt-in, in-memory single-process component. The main Cal45 frontend does not yet register these routes; no login session is accepted by the existing settings/calendar preview. A production launch requires a reverse proxy trusted TLS origin, safe account bootstrap and recovery, MFA/step-up rules, rate limiting, a session-store design for replicated servers, cookie/user-agent tests, a successful CI run and browser integration.

No cross-site auth, public registration or device-password web management is enabled.
