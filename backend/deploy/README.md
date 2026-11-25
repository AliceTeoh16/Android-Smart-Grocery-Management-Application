# Free online hosting: Render Free + Turso Free

## Inventory unit and storage update

The inventory form now saves units and physical storage locations. `1 dozen` is saved as `12 pcs`,
`0.5 kg` as `500 g`, and `1.25 L` as `1250 ml`. A pack, bottle or carton remains a package count;
the app does not guess its contents. The preview and low-stock label show the base unit before saving.
Total purchase prices are divided across the saved base quantity so budgets and purchase history
count the amount paid once. Units are also preserved in purchase history after item deletion.

Room schema 14 adds default values for existing rows without deleting data or pending syncs.
Update the sharing backend before distributing the 1.2 APK: Render's `/health` should report
`inventoryUnits: true`. The optional metadata fields remain compatible with older clients.
Personal unit/storage information follows the existing private scope and is not synchronized.
AI prompts, settings, parsing, recognition and ingredient generation are unchanged.

The default `render.yaml` now selects **Render Free**, with no paid disk. Turso Free stores the accounts and household database outside Render, so Render restarts or idle sleep do not erase it. Both providers have free-tier usage limits. Existing Gemini API usage and its provider quotas are unchanged.

| Part | Where it runs |
| --- | --- |
| Android app and private records | Room SQLite on each phone |
| Account / household API and existing AI service | One Render Free web service, behind one HTTPS address |
| Account profiles and shared pantry, shopping, budget and purchase history | Turso Free hosted SQLite-compatible database |

Only the sharing storage connection and hosting setup change. AI server files, prompts, models, Gemini logic, matching and ingredient generation remain unchanged. Turso credentials belong only on the backend, never in the APK or GitHub.

## Step 1: Create the free database

1. Open https://app.turso.tech/ and create a free account. You can use your GitHub account.
2. Choose the **Free** plan and create an empty database named `shelfwise`.
3. Copy its database URL and create a database auth token. Keep the token private; enter it only in Render's environment settings and the hidden import prompt on your computer.

The app supports the standard `libsql://...`, `turso://...` or HTTPS database URL supplied by Turso. No separate Turso CLI or WSL installation is required for the included import tool.

## Step 2: Connect Render to GitHub

Open https://dashboard.render.com/, sign in with GitHub, choose **New > Blueprint**, and connect:

`AliceTeoh16/Android-Smart-Grocery-Management-Application`

Use branch `main` and Blueprint path `render.yaml`. The resource should be named `shelfwise-free-backend`, with plan **Free** and **no persistent disk**. Review the displayed configuration before deploying. The optional paid setup is now separate in `render.paid.yaml`; do not select that file for the free setup.

If you already created an older paid service, this free template creates a separate service. It does not delete an existing paid service or its disk. Preserve a backup and confirm the new service works before removing old resources in Render.

Fill in these environment values:

| Variable | Value |
| --- | --- |
| `GEMINI_API_KEY` | Your existing key, unchanged |
| `GEMINI_MODEL` | Copy the existing value unchanged if locally configured |
| `GEMINI_FALLBACK_MODELS` | Copy the existing value unchanged if locally configured |
| `SHARING_STORAGE` | `turso` |
| `TURSO_DATABASE_URL` | Your Turso database URL |
| `TURSO_AUTH_TOKEN` | Your private Turso database token |
| `SHARING_IMPORT_MODE` initially | `true` |

The Blueprint supplies Node 24 and the build/start commands. Do not import the local AI `PORT` value. Keep `SHARING_IMPORT_MODE=true` until existing household data has been imported. In this setup mode `/health` returns `service: "shelfwise-import"`; app requests are paused and no new account database is created.

For manual **Web Service** setup instead of a Blueprint:

| Field | Value |
| --- | --- |
| Root directory | `backend` |
| Build command | `npm ci --omit=dev && npm --prefix sharing ci --omit=dev` |
| Start command | `node deploy/start.mjs` |
| Runtime | Node 24 |
| Plan | Free |
| Region | Singapore |
| Health check | `/health` |

## Step 3: Move existing accounts and households

First sync each phone with the existing local server, then close the app on all phones while switching servers. Avoid new changes against the old server after taking the snapshot. Local personal grocery records are not uploaded.

In **computer PowerShell**, from `C:\Users\Asus\Downloads\FYP`, replace the sample URL and run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Import-CloudDatabase.ps1 -DatabaseUrl 'libsql://YOUR-DATABASE.turso.io'
```

Paste the Turso auth token into the hidden prompt. The helper:

- Saves a consistent private snapshot under `build/cloud-deploy`.
- Checks the exact household-server schema and database integrity before sending data.
- Rejects Android Room databases.
- Imports accounts, hashed credentials, household IDs, members, records and mutation/retry IDs atomically.
- Refuses to overwrite an existing hosted account or household.
- Records a snapshot fingerprint so a lost commit response can be retried safely.

The result must say `imported` or `already-imported`. If it cannot confirm the import, leave setup mode on and retry the **same snapshot** printed by the helper:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Import-CloudDatabase.ps1 -DatabaseUrl 'libsql://YOUR-DATABASE.turso.io' -SnapshotFile 'C:\FULL\PATH\TO\households-transfer-SAVED-TIMESTAMP.sqlite'
```

Do not commit the snapshot or token. The snapshot contains server account information. Existing hosted data is never cleared automatically. If the target is already in use, use a new empty Turso database or arrange a separate migration; do not delete data to bypass the check.

For a large database that exceeds interactive transaction limits, use Turso's documented `turso db create NAME --from-file SNAPSHOT.sqlite` import instead. The included atomic import is intended for a small grocery/FYP database. If starting from a genuinely empty server database, you can enable the live service without an import.

## Step 4: Enable the service

In Render's Environment settings set `SHARING_IMPORT_MODE=false`, save and deploy. Open the actual Render HTTPS URL followed by `/health`. It should include:

```json
{"ok":true,"service":"shelfwise-gateway","sharing":"household-sharing","sharingStorage":"turso"}
```

Additional existing AI capability fields are expected. Confirm the same model/capabilities as the local AI health response. Health checks do not invoke Gemini generation.

An invalid or missing Turso connection makes the service fail instead of silently using an empty local database. Do not change `SHARING_STORAGE` to `sqlite` on Render Free.

## Step 5: Build and install the cloud APK

In computer PowerShell replace the example URL with your real healthy service URL:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-CloudApk.ps1 -ServerUrl 'https://YOUR-SERVICE.onrender.com/'
```

This verifies that the host uses Turso and creates `build/cloud-deploy/ShelfWise-cloud.apk`. Install it as an update on both phones using the same signing key, without uninstalling or clearing storage. Sign in with the existing account so cached household links can move to the new address. Generate a fresh invitation link after switching servers.

Render Free has no persistent disk or SSH access. Distribute the built APK through your existing APK download link, Google Drive, GitHub Releases or Firebase App Distribution. The local server's `/download/shelfwise.apk` route is not a persistent online APK store on this free deployment.

## Step 6: Test with different networks

Use Wi-Fi on one phone and mobile data on the other. Check login, invite joining, shared pantry, shopping, budget and purchase history. Confirm a personal grocery record is not visible to another household member. Make an offline shared purchase, reconnect, and check it appears once. Restart/redeploy the Render service and confirm existing accounts and household data remain available.

Render Free sleeps after about 15 idle minutes, and waking can take about a minute. Account/sharing HTTPS requests allow additional startup time. Existing AI request logic/settings are unchanged; if an AI request starts while the service is waking, wait for the service and retry. Free quotas still apply; these files do not enable automatic paid upgrades.

After backend changes, push the code and manually deploy in Render; automatic deploys are disabled in the template. Android changes or a server URL change require a newly built APK. Keep private database backups; Turso also provides export tools. The Node import helper does not require access to Render's filesystem.

## Verification and remaining deployment work

```powershell
npm --prefix backend/sharing ci --ignore-scripts
node --test backend/sharing/server.test.js backend/sharing/storage.test.js backend/deploy/start.test.mjs backend/deploy/import-turso.test.mjs backend/gateway/server.test.js
```

The tests exercise local SQLite plus the real Turso SDK against an isolated local protocol fixture, including rollback, account privacy, cross-household restrictions, 200-item batches, lost commit responses and safe account migration. They do not connect to a real hosted account or invoke Gemini. Real cloud connectivity is checked after you create your Turso/Render accounts and configure the credentials.

Before a broad public release, add account enforcement and rate limits at the gateway for the currently unrestricted AI endpoints. This can be done without changing AI prompts or generation logic. The existing account service also does not provide email verification or password-reset delivery.

For the optional paid/local-disk deployment, see [README-paid.md](README-paid.md) and use Blueprint path `render.paid.yaml` with `SHARING_STORAGE=sqlite`.

Official references: [Render Free](https://render.com/docs/free), [Turso Free pricing](https://turso.tech/pricing), [Turso SDK](https://docs.turso.tech/sdk/ts/reference), [SQLite file import](https://docs.turso.tech/cli/db/create).
