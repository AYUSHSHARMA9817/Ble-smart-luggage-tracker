# BLE Smart Luggage Tracker

BLE Smart Luggage Tracker is a three-part system:

- ESP32-C3 firmware that broadcasts BLE advertisements
- an Android app that can act as owner client, scanner relay, and admin provisioning client
- a Node.js backend that stores state, serves lightweight web pages, and evaluates alerts

The system tracks bag open/closed state, recent sightings, scanner location when available, RSSI-based proximity, geofence transitions, and health-related tracker events.

## Repository Layout

- [`main/`](./main): ESP-IDF firmware
- [`android-app/`](./android-app): Android app
- [`backend/`](./backend): backend service
- [`docs/`](./docs): supporting assets and compiled report artifact
- [`reports/`](./reports): maintained technical, user, and deployment documentation
- [`render.yaml`](./render.yaml): Render deployment blueprint

## Architecture

```text
ESP32-C3 tracker
  -> BLE advertisement
Android scanner
  -> POST /api/sightings
Node.js backend + libsql
  -> owner app, admin page, dashboard
Android owner app
```

## Quick Start

### Backend

```bash
cd backend
npm start
```

Local health check:

```bash
curl http://localhost:8787/api/health
```

The backend uses libsql. In production, point it at Turso. If `TURSO_DATABASE_URL` is unset, local development falls back to `file:./data/ble-tracker.db`.

### Android app

```bash
cd android-app
./gradlew :app:assembleDebug
```

Current build requirements:

- Java 17
- Android SDK 35
- minimum Android version: API 26

### Firmware

```bash
idf.py build
idf.py -p /dev/ttyUSB0 flash monitor
```

Current target from [`sdkconfig`](./sdkconfig):

- `esp32c3`

Key firmware defaults from [`main/ble_tracker.c`](./main/ble_tracker.c):

- `DEVICE_ID = 0x0000AB01`
- `MANUFACTURER_ID = 0xFF01`
- reed switch pin `GPIO_NUM_5`
- heartbeat every 6 seconds
- self-test every 24 hours

## Main Workflows

### Provision a tracker

An admin can create a one-time claim code through:

- the backend page at `/admin.html`
- the backend API
- the Android app admin mode

### Claim a tracker

An owner signs in and submits:

- tracker device ID
- display name
- one-time manual claim code

### Relay sightings

Scanner phones:

- scan for manufacturer ID `0xFF01`
- parse the 10-byte tracker payload
- optionally attach location
- queue failed uploads locally
- retry the queue every 10 seconds

### Evaluate alerts

The backend currently generates:

- `BAG_OPENED`
- `BAG_CLOSED`
- `STATE_CHANGE_SIGNAL`
- `SELF_TEST_HEALTH`
- `GEOFENCE_EXIT`
- `GEOFENCE_ENTRY`
- `PROXIMITY_LOST`
- `PROXIMITY_RESTORED`

## Deployment

Only the backend must be hosted.

Useful files:

- [`backend/Dockerfile`](./backend/Dockerfile)
- [`backend/Procfile`](./backend/Procfile)
- [`render.yaml`](./render.yaml)
- [`backend/scripts/import-json-to-turso.mjs`](./backend/scripts/import-json-to-turso.mjs)

## Documentation

- [`backend/README.md`](./backend/README.md)
- [`android-app/README.md`](./android-app/README.md)
- [`docs/android_integration.md`](./docs/android_integration.md)
- [`reports/technical-documentation.md`](./reports/technical-documentation.md)
- [`reports/user-documentation.md`](./reports/user-documentation.md)
- [`reports/deployment-guide.md`](./reports/deployment-guide.md)

## Notes

- The firmware currently advertises a fixed external-power-good state instead of live battery sensing.
- The Android manifest still allows cleartext traffic for local HTTP testing.
- The repository includes generated directories such as `build/`, `android-app/build/`, and `backend/node_modules/`.
