# Cross-device household sharing (local server)

This service runs separately from the existing Gemini backend. It has its own port and SQLite database and does not load or change any AI settings, prompts, models, or endpoints.

## Start the sharing server

Use Node.js 24 (the tested version). From the repository root:

```powershell
node backend/sharing/server.js
```

The default listener is `0.0.0.0:8790`. Durable account and household storage is in `backend/sharing/data/households.sqlite`. Keep this directory when restarting or updating the service. Only run one server process against that database.

Optional environment variables: `SHARING_HOST`, `SHARING_PORT`, and `SHARING_DATA_DIR`. These are independent of the AI backend's configuration.

## Connect two phones

1. Put the computer and phones on the same trusted Wi-Fi network. Find the computer's Wi-Fi IPv4 address with `ipconfig`.
2. Start the sharing server and allow its port through the computer's firewall on that private network if necessary.
3. On each phone, tap **Sharing server** on Log in/Create account and enter `http://COMPUTER_IP:8790/`. Android emulators on this computer use `http://10.0.2.2:8790/` by default.
4. Create separate accounts (or sign in to an existing sharing account).
5. On the first phone, create a household. On the second, choose **Join household** and enter the first phone's six-digit code. The invitation link also includes the server address; changing to a link's server requires confirmation.
6. Select **Household** to read or edit the shared pantry, shopping list, or monthly budget. Changes sync automatically while the app process is running; **Sync now** is available in Share & Connect.

For an account and household created before this feature, open Share & Connect → **Connect account** and verify the existing password. The owner can then tap **Enable cross-device sharing for this household**. That explicit action publishes its shared rows and assigns a server-issued invitation code. Personal rows are excluded. Existing local credentials must match before a sharing identity can claim that local profile.

## Privacy and synchronization

- Personal inventory, personal shopping, personal budgets, and checklist history stay on the device. The sync serializer has an explicit household-field allowlist; the server rejects private scope/identity fields and unknown collections.
- Each request uses an authenticated, expiring bearer token. The server checks active household membership on every household read/write. Pending invitations do not grant access. Owner-only edits and invitations are enforced on the server.
- Server passwords are stored as salted scrypt hashes. Device token storage uses an Android Keystore AES-GCM key. Existing local password hashes support offline access to cached device data.
- Offline shared changes enter a transactional Room outbox. Exact in-flight payloads and operation IDs survive timeouts, app restarts, and lost acknowledgements. Server idempotency prevents a retried stock addition from being counted twice.
- Stock and shopping quantities merge as deltas; changed fields are patched independently. The last server-accepted budget limit wins. Deletes create tombstones, so stale edits cannot resurrect removed records. Spending logs are append-only and remain after pantry deletion.
- Device-local numeric item IDs are translated to stable shared IDs, including shopping-to-pantry references. Remote imports do not generate outgoing changes. A membership revocation removes cached access and discards unauthorized pending writes.
- Sync resumes on app launch and periodically while the process is alive. This implementation does not promise background push notifications when Android has stopped the app.

The local HTTP setup is for trusted-network development. Use an HTTPS reverse proxy and protected durable storage before exposing this service outside that network. Clients reject public HTTP addresses. Email verification and password-reset delivery are not configured in this local service.

## Verification

```powershell
node --test backend/sharing/server.test.js
```

Android's `CrossDeviceSharingTest` uses two independent Room device databases against an isolated server on port 8791. Start that instance with a separate `SHARING_DATA_DIR` before running the test. It verifies joins, shared updates, private-data exclusion, ID mapping, concurrent quantity changes, spending history, persistent retry after a lost acknowledgement, and migration from Room v11.

The app uses the configurable `SHARING_BASE_URL` Gradle property as its default server. This setting is separate from `AI_BASE_URL`; changing it does not change the AI backend address.
