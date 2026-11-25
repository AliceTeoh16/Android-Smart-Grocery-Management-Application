# ShelfWise single-address gateway

This gateway exposes the existing account/household service and AI service through one address. It forwards `/v1/*` to port 8790 and other requests to the existing AI service on port 8787. Request bodies, authorization headers, responses, and AI behavior remain in those services. Neither database is replaced.

Run `Start-Phone-Demo.bat` in the project folder to start all services and build the phone APK. The launcher detects the computer's Wi-Fi address and supplies `-PAPP_BASE_URL=http://<computer-ip>:8788/` to Gradle. Both Android service clients use that address. The distributed build ignores old saved server preferences and hides server setup from normal screens. Log in again after an address change; successful authentication to the same remote account preserves cached household IDs and pending offline changes.

Run `Allow-Phone-WiFi.bat` once to allow only Node's gateway port 8788 from the local subnet on the Wi-Fi interface. This requires Windows administrator approval. Keep the computer awake and both devices on the same Wi-Fi. If its IP changes, rerun both launchers and install the rebuilt APK as an update. For an address that remains stable across networks, deploy these services behind a public HTTPS domain and build with that domain as `APP_BASE_URL`.

`GET /health` checks both services and preserves the AI capability fields used by the app. `GET /health/ai` and `GET /health/sharing` inspect each service independently. Service errors returned by the gateway do not expose internal network addresses.

After a successful build, open `http://<computer-ip>:8788/download/shelfwise.apk` in the phone's browser to download the latest APK. This fixed route serves only `build/phone-demo/ShelfWise-phone-debug.apk` and disables browser caching. Install it as an update to preserve local data.

Run `node --test backend/gateway/server.test.js` from the project root. Tests use temporary in-memory household storage and local stub services; they do not invoke Gemini or modify real accounts.

For free online hosting, see `../deploy/README.md`. The gateway adds `sharingStorage` to aggregate health so the APK build helper can confirm that household data uses Turso. AI capability fields and proxy payloads remain unchanged. Render Free does not provide durable APK file storage; distribute a cloud APK separately.
