# Cal45 Web authentication — security primitives (under development)

Cal45 includes **unit-tested foundations**, not yet a complete login system:

- RFC 6238 TOTP verification with a 30-second step and +/- one step tolerance.
- TOTP anti-replay: caller receives the accepted counter and must update it atomically.
- 128-bit random single-use recovery codes, stored by digest and consumed transactionally.
- Opaque 256-bit server-side sessions, 8-hour maximum TTL, revocation and 256-bit CSRF token comparison.
- Session cookie defaults: Secure, HttpOnly, SameSite=Strict and __Host- prefix.

**None of these functions are connected to public routes yet.** The /settings page is still a preview. No password login, WebAuthn ceremony or MFA enrollment is active.

## Integration requirements before enabling login

1. WebAuthn/FIDO2 using a maintained library; verify origin, RP ID, challenge expiration, user verification and credential counters.
2. Store verified passkey credentials and encrypted TOTP enrollment secrets in a durable database.
3. Server-side atomic, per-account consumption of TOTP counters and recovery-code hashes.
4. Secure MFA enrollment confirmation, step-up verification, account recovery and session rotation.
5. Login rate limits, origin and CSRF enforcement, secure HTTPS reverse proxy and consistent account lifecycle with DAV.
6. Replace the current in-memory session map with a persistent, bounded store if the web service becomes multi-process or multi-replica.
7. Do not expose self-service app-token issuance until all these controls are active and tested.

The unit-test suite includes RFC 6238 test vectors, code-reuse checks, CSRF checks and session expiration/revocation. **No production security claim is made.**
