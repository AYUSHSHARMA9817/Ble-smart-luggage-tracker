# BLE Tracker Backend

This service accepts BLE tracker sightings from Android scanner phones, maps trackers to owners, stores device state in Turso, and generates notifications for bag state changes, geofence exit and re-entry, proximity loss, and proximity restoration.

## What To Deploy

Only the backend is deployed online. The Android app connects to this backend over HTTP/HTTPS and uploads sightings from nearby phones.

## Environment Variables

Copy `.env.example` and set these values in your platform:

- `PORT`: HTTP port. Defaults to `8787`.
- `ADMIN_REGISTRATION_SECRET`: required for admin tracker registration creation.
- `TURSO_DATABASE_URL`: libsql/Turso database URL.
- `TURSO_AUTH_TOKEN`: Turso auth token.
- `ALERT_PROXIMITY_STALE_MS`: optional threshold for `PROXIMITY_LOST`. Defaults to `60000`.

## Local Run

```bash
cd backend
npm start
```

Health check:

```bash
curl http://localhost:8787/api/health
```

## Local Auth API

Create account:

```bash
curl -X POST http://localhost:8787/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Ayush","email":"ayush@example.com","password":"secret123"}'
```

Login:

```bash
curl -X POST http://localhost:8787/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ayush@example.com","password":"secret123"}'
```

## Tracker Registration

Create an admin registration:

```bash
curl -X POST http://localhost:8787/api/admin/device-registrations \
  -H 'Content-Type: application/json' \
  -d '{"adminSecret":"YOUR_ADMIN_SECRET","deviceId":"0x0000AA01","manualCode":"ABCD1234","note":"demo unit"}'
```

Claim a tracker as an owner:

```bash
curl -X POST http://localhost:8787/api/devices/register \
  -H 'Authorization: Bearer YOUR_AUTH_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"0x0000AA01","displayName":"Blue Bag","manualCode":"ABCD1234"}'
```

## Sighting Upload

```bash
curl -X POST http://localhost:8787/api/sightings \
  -H 'Content-Type: application/json' \
  -d '{
    "scannerUserId":"scanner_android_1",
    "manufacturerId":"0xFF01",
    "deviceId":"0x0000AA01",
    "rssi":-68,
    "location":{"lat":26.1440,"lng":91.7357,"accuracyMeters":18},
    "packet":{
      "bagState":1,
      "batteryLevel":2,
      "seqNum":17,
      "packetType":1,
      "healthStatus":0,
      "daysSinceChange":0
    }
  }'
```

## Online Deployment

### Render

This repo includes [render.yaml](../render.yaml), a `Dockerfile`, and a `Procfile`.

Recommended setup:

1. Create a new Render service from the repo.
2. Use the included `render.yaml`.
3. Set `ADMIN_REGISTRATION_SECRET`.
4. Set `TURSO_DATABASE_URL` and `TURSO_AUTH_TOKEN`.
5. Import any existing `backend/data/app.json` data with `npm run db:import-json`.

### Generic Docker Host

```bash
cd backend
docker build -t ble-tracker-backend .
docker run -p 8787:8787 \
  -e PORT=8787 \
  -e TURSO_DATABASE_URL=libsql://your-database-name-your-org.turso.io \
  -e TURSO_AUTH_TOKEN=your-token \
  -e ADMIN_REGISTRATION_SECRET=change-me \
  ble-tracker-backend
```
