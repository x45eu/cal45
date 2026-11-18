# Cal45 initial passkey enrollment HTTP adapter

The `InitialEnrollment` handler implements the bridge from an **operator-issued** `cal45_setup_...` token to a WebAuthn browser registration ceremony. It is located in the isolated `passkeys/` Go module and is **NOT enabled by the running Cal45 frontend**.

1. A trusted operator provisions an active Cal45 username and issues a 10-minute one-time setup grant with the CLI.
2. The browser sends a JSON POST to `/auth/passkey/enroll/begin` with `username` and `setupToken`, using a strict matching HTTPS `Origin` header (never put the setup token into a URL).
3. Cal45 consumes the grant atomically, checks the account has **no already registered passkey**, ensures the account's persistent random WebAuthn user handle exists, then generates a WebAuthn registration challenge.
4. A 120-second, host-only `Secure`/`HttpOnly`/`SameSite=Strict` browser cookie binds the ceremony. Server side also tracks the binding in an expiring, single-use map.
5. The browser creates a passkey using `navigator.credentials.create` and POSTs its WebAuthn attestation JSON to `/auth/passkey/enroll/finish`, with the ceremony ID in `X-Cal45-Ceremony`. The server checks cookie and ceremony bindings and calls the library's cryptographic registration verifier.
6. On successful verification, the credential is saved durably. **No login session is created**; the browser must log in with its newly registered passkey.

This handler is not auto-registered; an embedding server must explicitly instantiate it. The operator-issued grant and account allowlist are the sole enrollment prerequisites, so production exposure also requires rate limiting, HTTPS proxy/hardening, origin configuration, full CI pass and hardware/browser testing.

Additional passkeys on an existing account must use the separate step-up-protected registration flow, **not** another setup grant. A failed or interrupted ceremony consumes the setup grant and requires the operator to issue a new one.

This flow is an integration component, not a deployed public user-facing service.


## Static browser screen

`web/enroll.html` and `web/enroll.js` provide the Cal45-branded browser ceremony, with native `navigator.credentials.create` and no third-party JavaScript. The setup token is entered into a password field and posted in the HTTPS request body, **never placed in a link**. The token is cleared from the form after the first request. These static assets are not yet included in the running Go frontend and cannot be used until an explicitly approved enrollment deployment exists.
