# Smart Grocery

Smart Grocery is an offline-first Android grocery management application with AI-assisted recipe generation, voice and photo inventory entry, shopping and budget tracking, expiry alerts, purchase history, and optional cross-device household sharing.

The Android app stores personal data locally with Room/SQLite. A Node.js backend provides Gemini-powered inventory scanning, voice transcription, and recipe checklist generation. A separate Node.js server synchronizes household inventory, shopping lists, budgets, and spending records between devices on a trusted network.

## Features

- Inventory management with categories, quantities, thresholds, purchase dates, and expiry dates
- Low-stock and expiry notifications
- Shopping list with household sharing and QR-based joining
- Monthly budget limits and purchase-history spending logs
- Voice input for inventory commands and voice transcription
- Camera/photo batch scanning for groceries and household products
- AI-generated recipes with synchronized ingredients, shopping shortages, stock deductions, and cooking steps
- Recipe checklist history
- Local accounts and account-scoped personal data
- Optional cross-device household synchronization
- Offline operation for personal inventory and data stored on the device

## Project Structure

```text
app/                  Android application module
compose-pages/        Additional Jetpack Compose source used by the app module
backend/              Gemini AI and voice/photo backend
backend/sharing/      Cross-device household synchronization server
gradle/               Gradle wrapper configuration
app/src/test/         Android unit tests
app/src/androidTest/  Android instrumentation tests
```

## Technology

### Android

- Kotlin
- Jetpack Compose and Android Views
- MVVM-style ViewModels
- Room / SQLite
- Kotlin Coroutines and Flow
- Retrofit
- Android SpeechRecognizer
- Android notifications and boot receivers

### Backend

- Node.js
- Express
- Google Gemini API through `@google/genai`
- Node built-in SQLite for household sharing
- dotenv
- CORS

## Requirements

- Android Studio with an Android SDK
- Android API level 24 or newer for the app
- JDK 17 or newer
- Node.js 20 or newer for `backend`
- Node.js 22.13 or newer for `backend/sharing`
- A Gemini API key for AI recipe, voice, and photo features
- A trusted local Wi-Fi network for cross-device household sharing

## Setup

### 1. Clone the project

```powershell
git clone <repository-url>
cd <repository-directory>
```

### 2. Configure Android Studio

Open the repository root in Android Studio and let Gradle sync.

The app uses these Gradle properties:

| Property | Default | Purpose |
|---|---|---|
| `AI_BASE_URL` | `http://10.0.2.2:8787/` | Gemini backend address |
| `SHARING_BASE_URL` | `http://10.0.2.2:8790/` | Household sharing server address |

The emulator defaults assume both Node servers run on the development computer. For a physical Android device, set both URLs to the computer's LAN IP address.

```powershell
.\gradlew.bat assembleDebug -PAI_BASE_URL=http://192.168.1.25:8787/ -PSHARING_BASE_URL=http://192.168.1.25:8790/
```

Replace `192.168.1.25` with the development computer's Wi-Fi IPv4 address.

### 3. Configure the Gemini backend

```powershell
cd backend
npm install
Copy-Item .env.example .env
```

Set a real key in `backend/.env`:

```text
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-3.5-flash-lite
GEMINI_FALLBACK_MODELS=gemini-3.5-flash-lite,gemini-3.1-flash-lite,gemini-flash-lite-latest,gemini-3.8-flash
PORT=8787
```

Never commit `.env` or put the Gemini key into Android `BuildConfig`, source code, or resources.

Start the AI backend:

```powershell
npm start
```

For development with automatic restarts:

```powershell
npm run dev
```

Check the backend:

```text
http://localhost:8787/health
```

### 4. Start household sharing

The sharing server is optional. From the repository root:

```powershell
node backend/sharing/server.js
```

The default address is `http://localhost:8790/`. It listens on all interfaces by default.

Optional environment variables:

| Variable | Default |
|---|---|
| `SHARING_HOST` | `0.0.0.0` |
| `SHARING_PORT` | `8790` |
| `SHARING_DATA_DIR` | `backend/sharing/data` |

Persistent household data is stored in SQLite under `backend/sharing/data/households.sqlite`. Keep this directory between restarts.

See [backend/sharing/README.md](backend/sharing/README.md) for account, household, synchronization, privacy, and firewall setup.

## Running the Android App

1. Start the AI backend on port `8787`.
2. Optionally start the sharing server on port `8790`.
3. Open the repository in Android Studio.
4. Select an emulator or physical device.
5. Run the `app` configuration.

On a physical device, grant microphone and notification permissions when prompted. The camera can be used for photo batch scanning.

## API Overview

### AI backend

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/health` | Backend and scanner capability status |
| `POST` | `/recipes/generate` | Generate a recipe checklist and cooking steps |
| `POST` | `/ingredients/scan` | Recognize products from a compressed JPEG |
| `POST` | `/voice/transcribe` | Transcribe inventory or recipe voice input |
| `GET` | `/ingredients/scan/capabilities` | Return photo scanner capabilities |

The AI API key remains on the backend server and is not bundled in the Android app.

## Testing

### Backend tests

From `backend`:

```powershell
node --test ingredient-scan.test.js voice-transcription.test.js checklist-steps.test.js
```

From the repository root:

```powershell
node --test backend/sharing/server.test.js
```

### Android unit tests

```powershell
.\gradlew.bat testDebugUnitTest
```

### Android instrumentation tests

Start the required app/emulator setup, then run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## Security Before Publishing

Before making this repository public:

- Remove and rotate any API keys that may already have been committed or shared.
- Keep `GEMINI_API_KEY` in `backend/.env`.
- Confirm `.env`, `local.properties`, signing files, keystores, logs, databases, build outputs, and `node_modules` are ignored.
- Review Git history, because deleting a key from the latest files does not remove it from earlier commits.
- Use HTTPS and protected storage before exposing either server outside a trusted network.
- The provided sharing server is designed for trusted local-network development.

## Troubleshooting

### Android cannot reach the backend

- For an emulator, verify `http://10.0.2.2:8787/`.
- For a physical phone, use the computer's LAN IP and ensure the phone and computer share the same network.
- Allow Node ports through the Windows firewall.
- Keep `usesCleartextTraffic` and the current HTTP URL for trusted local development only.

### Gemini features return not configured

- Confirm `GEMINI_API_KEY` is set in `backend/.env`.
- Restart `node server.js` or `npm start` after editing `.env`.
- Verify `GET /health` reports the backend provider and expected model.

### Sharing does not synchronize

- Confirm port `8790` is reachable.
- Start one sharing server instance against the persistent database.
- Check `SHARING_BASE_URL` on the Android app.
- Recreate or join the household after confirming the server address.

## Documentation

Additional project documentation is available in:

- [backend/README.md](backend/README.md)
- [backend/sharing/README.md](backend/sharing/README.md)
- [frontend.md](frontend.md)
- [backend.md](backend.md)
- [END_TO_END_ARCHITECTURE_WORKFLOW.md](END_TO_END_ARCHITECTURE_WORKFLOW.md)
- [PROJECT_STRUCTURE_FUNCTION_ALGORITHM.md](PROJECT_STRUCTURE_FUNCTION_ALGORITHM.md)
- [REQUIREMENT_EXISTENCE_CHECK.md](REQUIREMENT_EXISTENCE_CHECK.md)

## UI Design
### 1. Home Page Overview
<img width="412" height="917" alt="Home overview" src="https://github.com/user-attachments/assets/08b57d1d-b580-4d41-9234-8813f0d3846d" />

### 2. Inventory Page
<img width="412" height="917" alt="Smart Inventory" src="https://github.com/user-attachments/assets/b594ad57-176b-47fc-a4f7-c8d539dcf79d" />

### 3. Shopping List Page
<img width="412" height="917" alt="Shopping List" src="https://github.com/user-attachments/assets/10a80f53-9773-42df-9e92-187634368966" />

### 4. AI-Checklist Page
<img width="412" height="917" alt="AI Checklist" src="https://github.com/user-attachments/assets/c0a5e6f4-4d38-4d63-acd1-c3c9c4d39297" />

### 5. Budget Tracking Page
<img width="412" height="917" alt="Budget Tracker" src="https://github.com/user-attachments/assets/f93fa449-be0f-40d4-b373-ca304195d180" />




