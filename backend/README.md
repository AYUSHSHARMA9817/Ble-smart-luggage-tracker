# BLE Tracker Backend

This service accepts BLE tracker sightings, stores application state through libsql, serves a simple owner dashboard at `/`, serves an admin registration page at `/admin.html`, and exposes the API used by the Android app.

## Runtime

- Node.js `>=20`
- ES modules
- Persistence through `@libsql/client`
- Default listen port: `8787`

## Persistence Model

The backend stores one JSON application-state document in a single libsql table row.

- In production, point libsql at Turso with `TURSO_DATABASE_URL` and `TURSO_AUTH_TOKEN`.
- For local development, if `TURSO_DATABASE_URL` is unset, the backend falls back to `file:./data/ble-tracker.db`.

Top-level state collections:

- `users`
- `devices`
- `geofences`
- `events`
- `alerts`
- `notifications`
- `scannerSightings`
- `sensorReadings`
- `sessions`
- `deviceRegistrations`
- `securityEvents`

## Environment Variables

See [`backend/.env.example`](./.env.example).

- `PORT`
  HTTP port. Defaults to `8787`.
- `ADMIN_REGISTRATION_SECRET`
  Required for `POST /api/admin/device-registrations`.
- `TURSO_DATABASE_URL`
  Optional locally. In production this should be your Turso/libsql URL.
- `TURSO_AUTH_TOKEN`
  Turso auth token when required by the chosen libsql endpoint.
- `ALERT_PROXIMITY_STALE_MS`
  Milliseconds before a claimed device is considered out of contact. Defaults to `60000`.

## Local Run

```bash
cd backend
npm start
```

Health check:

```bash
curl http://localhost:8787/api/health
```

Syntax check:

```bash
cd backend
npm run check
```

## Static Pages

- `/`
  Lightweight dashboard from [`public/index.html`](./public/index.html)
- `/admin.html`
  Admin tracker registration page from [`public/admin.html`](./public/admin.html)

The admin page lets an operator:

- enter `ADMIN_REGISTRATION_SECRET`
- register a tracker `deviceId`
- optionally provide a manual claim code
- optionally add a note

If no manual code is provided, the backend generates one. Only the hashed code is stored; the plain code is returned once in the response.

## API Summary

Authentication:

- `POST /api/users`
- `POST /api/auth/login`
- `POST /api/auth/google`
- `GET /api/me`
- `GET /api/bootstrap`

Device management:

- `POST /api/admin/device-registrations`
- `POST /api/devices/register`
- `GET /api/devices`
- `DELETE /api/devices/:deviceId`
- `POST /api/devices/:deviceId/monitoring`

Scanner ingestion:

- `POST /api/sightings`

Owner data:

- `GET /api/events`
- `GET /api/alerts`
- `POST /api/alerts/ack`
- `GET /api/notifications`
- `GET /api/sensor-readings`
- `GET /api/geofences`
- `POST /api/geofences`
- `DELETE /api/geofences/:geofenceId`

Operations:

- `GET /api/health`

## API Notes

- `POST /api/sightings` does not require authentication.
- Rate limiting is in-memory and currently enforced as `120` requests per minute per `IP + path`.
- Sessions are bearer tokens with a 30-day expiry.
- The backend keeps only one active session per user; a new login replaces the previous one.
- Google sign-in calls Google's `tokeninfo` endpoint from the server, so the backend needs outbound internet access for that flow.

## Example Requests

Create an account:

```bash
curl -X POST http://localhost:8787/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Ayush","email":"ayush@example.com","password":"secret123"}'
```

Log in:

```bash
curl -X POST http://localhost:8787/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ayush@example.com","password":"secret123"}'
```

Create an admin registration:

```bash
curl -X POST http://localhost:8787/api/admin/device-registrations \
  -H 'Content-Type: application/json' \
  -d '{"adminSecret":"YOUR_ADMIN_SECRET","deviceId":"0x0000AB01","manualCode":"ABCD1234","note":"demo unit"}'
```

Claim a tracker:

```bash
curl -X POST http://localhost:8787/api/devices/register \
  -H 'Authorization: Bearer YOUR_AUTH_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"0x0000AB01","displayName":"Blue Bag","manualCode":"ABCD1234"}'
```

Upload a sighting:

```bash
curl -X POST http://localhost:8787/api/sightings \
  -H 'Content-Type: application/json' \
  -d '{
    "scannerUserId":"scanner_android_1",
    "manufacturerId":"0xFF01",
    "deviceId":"0x0000AB01",
    "rssi":-68,
    "location":{"lat":26.1440,"lng":91.7357,"accuracyMeters":18},
    "packet":{
      "bagState":1,
      "batteryLevel":3,
      "seqNum":17,
      "packetType":1,
      "healthStatus":0,
      "daysSinceChange":0
    }
  }'
```

Send a fake sensor packet while developing the app:

```bash
BACKEND_URL=http://localhost:8787 DEVICE_ID=0x0000AB01 npm run fake:sighting
```

The app reads the latest values from each owned device and the historical list from `GET /api/sensor-readings`.

## Deployment Files

- [`Dockerfile`](./Dockerfile)
- [`Procfile`](./Procfile)
- [`../render.yaml`](../render.yaml)
- [`scripts/import-json-to-turso.mjs`](./scripts/import-json-to-turso.mjs)

JSON import helper:

```bash
cd backend
npm run db:import-json
```
