# Deployment Guide — BLE Smart Luggage Tracker

This project has three components, but only one of them must be hosted publicly:

- ESP32-C3 firmware: flashed onto tracker hardware
- Android app: installed on phones
- backend: deployed on a reachable HTTP or HTTPS endpoint

The backend is the only network service in the system.

## What the Backend Needs

- Node.js `>=20`
- libsql persistence
- `ADMIN_REGISTRATION_SECRET`
- optional Turso credentials for production

Local development can run without Turso because the backend falls back to:

```text
file:./data/ble-tracker.db
```

Production should use Turso or another reachable libsql endpoint.

## Environment Variables

From [`backend/.env.example`](../backend/.env.example):

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8787` | HTTP listen port |
| `ADMIN_REGISTRATION_SECRET` | none | Required for admin tracker registration |
| `TURSO_DATABASE_URL` | unset | libsql database URL |
| `TURSO_AUTH_TOKEN` | unset | libsql auth token when needed |
| `ALERT_PROXIMITY_STALE_MS` | `60000` | Proximity-loss threshold in milliseconds |

## Render

The repository already includes [`render.yaml`](../render.yaml) and [`backend/Dockerfile`](../backend/Dockerfile).

Current blueprint settings in `render.yaml`:

- service type: `web`
- runtime: `docker`
- root directory: `backend`
- plan: `starter`

### Deploy Steps

1. Create a Render web service or blueprint deployment from this repository.
2. Apply the included [`render.yaml`](../render.yaml).
3. Set `ADMIN_REGISTRATION_SECRET`.
4. Set `TURSO_DATABASE_URL` and `TURSO_AUTH_TOKEN` if you want production persistence in Turso.
5. Deploy.

Verify:

```bash
curl https://your-render-service.onrender.com/api/health
```

## Docker

```bash
cd backend
docker build -t ble-tracker-backend .
docker run -p 8787:8787 \
  -e PORT=8787 \
  -e ADMIN_REGISTRATION_SECRET=change-me \
  -e TURSO_DATABASE_URL=libsql://your-db.turso.io \
  -e TURSO_AUTH_TOKEN=your-token \
  ble-tracker-backend
```

If you omit Turso environment variables, the backend uses the local `file:` database path inside the container filesystem.

## Data Migration

If you previously stored data in [`backend/data/app.json`](../backend/data/app.json), import it with:

```bash
cd backend
npm install
TURSO_DATABASE_URL=<your-url> \
TURSO_AUTH_TOKEN=<your-token> \
npm run db:import-json
```

## Android App Configuration After Deployment

The app default backend URL currently lives in:

- [`android-app/app/src/main/java/com/bletracker/app/data/Prefs.kt`](../android-app/app/src/main/java/com/bletracker/app/data/Prefs.kt)

Current value:

```kotlin
const val DEFAULT_BACKEND_URL = "https://ble-smart-luggage-tracker.onrender.com"
```

If your deployed backend uses a different hostname:

- update that constant before release, or
- override the backend URL from the Settings screen on each device

## HTTPS and Cleartext

The Android manifest currently sets:

```xml
android:usesCleartextTraffic="true"
```

That is useful for local testing with `http://` URLs. For a production-only HTTPS build, you may want to disable cleartext traffic before release.

## Admin Provisioning

Before an owner can claim a device, an admin must create a registration.

Available admin entry points:

- web page: `/admin.html`
- API: `POST /api/admin/device-registrations`
- Android app admin mode

Example:

```bash
curl -X POST https://your-service.example/api/admin/device-registrations \
  -H 'Content-Type: application/json' \
  -d '{
    "adminSecret":"YOUR_ADMIN_SECRET",
    "deviceId":"0x0000AB01",
    "manualCode":"ABCD1234",
    "note":"Blue bag tracker"
  }'
```

If `manualCode` is blank, the backend generates one.

## Deployment Checklist

- `GET /api/health` returns `200`
- the backend can create users and log in
- `POST /api/sightings` succeeds
- `/admin.html` loads
- at least one device registration can be created
- the Android app points at the correct backend URL
- if using Google sign-in, the backend has outbound internet access to Google token verification
