# Cal45 passkey auth-preview Docker profile

By default Cal45 starts the DAV gateway and static web preview only. The standalone passkey-auth server is **opt-in**, using an isolated Docker Compose profile.

First provision an active account and obtain its operator-issued bootstrap grant with the documented operator tools. Then set the required exact HTTPS DNS origin and start the preview:

```sh
export CAL45_AUTH_ORIGIN=https://cal.example.org
docker compose --profile auth-preview up -d --build
```

The profile adds only the loopback-bound port `127.0.0.1:8082`. The authentication service **must never be exposed directly over HTTP** to end users. A trusted TLS reverse proxy needs explicit HTTPS routes for `/auth/` pointing to `127.0.0.1:8082`, with headers and limits configured for a single origin. The existing Cal45 main web preview at `127.0.0.1:8080` and DAV gateway at `127.0.0.1:8081` stay unchanged.

For the initial passkey, `GET /auth/enroll` serves the setup page; after successful enrollment, `GET /auth/login` handles normal login. An authenticated user can view `GET /auth/account` and explicitly log out. The `/auth/account` page now issues/revokes app passwords for the currently logged-in user, requiring a fresh (within 5 minutes) passkey verification and CSRF protection. **The main web settings view is still not protected by this session**, and the auth-preview requires further production security review.

Note: **This profile is a developer preview, not a production release.** The real device-token UI, TOTP policy, recovery, complete proxy setup, active session authorization for calendar/contact web pages and multi-device browser tests remain to be implemented. Never publish this profile without a security review and verified HTTPS proxy configuration.

The auth container mounts the same protected `cal45_auth` named volume as the DAV gateway; protect this volume from unauthorized containers and operators. A default `docker compose up` does not launch the auth profile.
