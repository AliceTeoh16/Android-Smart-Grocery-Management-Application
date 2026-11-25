# Host ShelfWise online with Render

This setup runs the existing AI server, household server and gateway on one hosted service. Phones use one HTTPS address over any Wi-Fi network or mobile data. Your computer can be turned off after deployment. Android Room remains on each phone; personal inventory, shopping, budgets and history stay local.

The deployment adds startup and storage configuration. It does not edit AI prompts, models, matching, ingredient generation, recognition or Gemini request logic. Copy your existing Gemini environment values unchanged when configuring the host.

Render requires a paid service to attach a persistent disk. Review its current charges before creating the service. A free ephemeral filesystem would lose the shared SQLite database on restarts/redeploys. This template uses one service instance and one persistent disk; do not scale this SQLite setup to multiple instances.

## 1. Put the project in a private GitHub repository

Include `render.yaml` and the complete `backend` folder with its lockfile. The new root `.gitignore` excludes `.env`, local household database files, backups under `build`, dependencies and signing keys when using Git. Do not upload these files manually. Keep the Android signing key used for existing APK updates on your computer.

## 2. Create the Render service

1. Sign in at https://dashboard.render.com and connect your GitHub account.
2. Choose **New → Blueprint**, select the repository and use its `render.yaml`.
3. Review the service and disk cost before applying it. Render assigns your actual HTTPS URL; `https://your-service.onrender.com/` below is only an example.
4. Set `GEMINI_API_KEY` to the same value as your local `backend/.env`. In the service's **Environment** settings, also copy `GEMINI_MODEL` and `GEMINI_FALLBACK_MODELS` unchanged if your local environment defines them. Do not import a local `PORT` value: the hosting launcher gives each service its own port.
5. Confirm the persistent disk is mounted at `/var/data` and `SHARING_DATA_DIR` is `/var/data/households`.

For manual service creation, use these settings instead of the Blueprint:

| Setting | Value |
| --- | --- |
| Runtime | Node, version 24 |
| Region | Singapore |
| Root directory | `backend` |
| Build command | `npm ci --omit=dev` |
| Start command | `node deploy/start.mjs` |
| Health check | `/health` |
| Disk mount | `/var/data` |
| `SHARING_DATA_DIR` | `/var/data/households` |
| `APK_PATH` | `/var/data/downloads/ShelfWise.apk` |
| `SHARING_IMPORT_MODE` initially | `true` |

Initially the service deliberately starts in database import mode. `/health` returns `service: "shelfwise-import"` and `mode: "database-import"`. Application requests return a maintenance response. No sharing database is opened, so you can copy existing accounts safely before starting the live service.

## 3. Preserve your existing accounts and households

The local server's SQLite file contains the account identities, shared records and retry/idempotency information. Copy the entire database using a consistent backup, not a selection of tables or a live database file without its WAL contents.

First sync the phones against the existing local server, then close the app on all phones until the cloud APK is installed. Avoid further changes against the old server during this cutover. Personal phone databases are not uploaded.

In **computer PowerShell**, from `C:\Users\Asus\Downloads\FYP`, run:

```powershell
node backend/deploy/backup-sharing.mjs backend/sharing/data/households.sqlite build/cloud-deploy/households-transfer.sqlite
```

This creates a checked standalone SQLite snapshot, includes committed WAL contents and refuses to overwrite an existing backup. Use a new output filename if repeating it. The backup contains account information; keep it private.

Set up an SSH key in Render's account settings, following its SSH documentation. In your service's **Connect → SSH** dropdown, copy the actual `USER@HOST` part of the supplied SSH command. Replace the example value below:

```powershell
$renderRemote = 'YOUR_SERVICE_USER@YOUR_RENDER_SSH_HOST'
ssh $renderRemote 'mkdir -p /var/data/households /var/data/downloads'
scp build/cloud-deploy/households-transfer.sqlite "${renderRemote}:/var/data/households/households.sqlite"
Get-FileHash build/cloud-deploy/households-transfer.sqlite -Algorithm SHA256
ssh $renderRemote 'sha256sum /var/data/households/households.sqlite'
```

Verify the two hashes match. The destination filename must be exactly `households.sqlite`. Copy only while import mode is active. Do not overwrite an existing live cloud database; for a later migration, take a cloud backup and arrange a separate maintenance cutover.

If starting with no existing accounts or household data, skip the upload. Turning off import mode creates an empty database on the persistent disk.

## 4. Start the live hosted service

Set `SHARING_IMPORT_MODE=false` in Render's **Environment** settings and save/deploy. Open your real HTTPS URL followed by `/health`. It should return:

```json
{"ok":true,"service":"shelfwise-gateway","sharing":"household-sharing"}
```

Additional AI capability fields are expected. Health checks do not make paid Gemini generation calls. Compare `/health/ai` with the existing local AI health response to confirm the same model and capability settings. The existing gateway routes `/v1/*` to household sharing and AI routes to the unchanged AI service.

## 5. Build the APK for the real HTTPS address

After the service is working, run this in **computer PowerShell**, replacing the example URL:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-CloudApk.ps1 -ServerUrl 'https://your-service.onrender.com/'
```

The helper checks that the actual hosted service is healthy, builds `build/cloud-deploy/ShelfWise-cloud.apk`, verifies both bundled service addresses and keeps your Wi-Fi APK copy separate. This is an APK for testing, signed with the existing debug key. Install it as an update on each phone without uninstalling or clearing storage. A Play Store release needs your own release signing configuration.

To provide an APK download link through the same online service:

```powershell
scp build/cloud-deploy/ShelfWise-cloud.apk "${renderRemote}:/var/data/downloads/ShelfWise.apk.uploading"
ssh $renderRemote 'mv /var/data/downloads/ShelfWise.apk.uploading /var/data/downloads/ShelfWise.apk'
```

Then open `https://your-service.onrender.com/download/shelfwise.apk` on the phone. If you receive an unavailable response, confirm the upload and `APK_PATH` setting. Future APKs can be distributed through this link; cloud redeploys preserve the disk.

## 6. Test across networks

Turn Wi-Fi off on one phone and use mobile data. Sign in to the existing account, generate a new invitation link, join from the other phone and verify shared pantry, shopping and budget updates. Confirm a personal item remains visible only to its owner. Test an offline household change, reconnect, and confirm it syncs once. You can then turn off the local computer; the online service handles requests.

After backend-only code changes, push to GitHub and manually deploy the service in Render. The template disables automatic deploys. Rebuild and reinstall the APK after Android changes or a server URL change. Back up the SQLite database regularly using the included snapshot tool on the host, and download backups privately.

Hosting preparation is not a completed public release. The existing AI endpoints have no account enforcement or rate limiting at the gateway. Those controls should be added at the gateway before a broad public rollout to prevent arbitrary callers using the server's Gemini quota; this can be done without changing AI prompts or generation logic. Existing local authentication also has no email verification or password-reset delivery.

## Verification

```powershell
node --test backend/deploy/start.test.mjs backend/gateway/server.test.js
```

Tests use isolated storage and stub processes. They verify port separation, unchanged Gemini environment values, shutdown/failure handling, maintenance mode, WAL-safe snapshots and gateway behavior without calling Gemini.

Official documentation: [Render web services](https://render.com/docs/web-services), [persistent disks](https://render.com/docs/disks), [environment variables](https://render.com/docs/configure-environment-variables), [SSH and file transfer](https://render.com/docs/ssh), [Blueprint reference](https://render.com/docs/blueprint-spec).
