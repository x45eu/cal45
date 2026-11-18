# Cal45 Browser Passkey Login UI

This is the static login screen and dependency-free browser WebAuthn client for the isolated `passkeys/` module. It uses the native `navigator.credentials.get` API to request a passkey, then submits its assertion to the private opt-in Go HTTP adapter. No external scripts, Google services or analytics are needed.

**Not deployed or linked in the running Cal45 frontend.** This HTML preview must not be served as a functional authentication page until a verified session-issuing callback, rate limits, first-passkey onboarding, TLS origin configuration and full end-to-end testing exist. The server only accepts POST requests with the exact configured Origin header.

The JavaScript assumes the WebAuthn library serializes assertion options as `{publicKey:...}`, and that the finish endpoint receives the credential response JSON. Browser fallback conversion is included for platforms without `PublicKeyCredential.parseRequestOptionsFromJSON`; compatibility tests on GrapheneOS are still required.
