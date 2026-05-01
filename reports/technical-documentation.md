# Technical Documentation — BLE Smart Luggage Tracker

## System Overview

The system consists of:

1. ESP32-C3 firmware that broadcasts BLE advertisements
2. an Android app that can scan, own, and provision trackers
3. a Node.js backend that stores state and evaluates alerts

Data flow:

```text
ESP32-C3 tracker
  -> BLE advertisement
Android scanner
  -> POST /api/sightings
Node.js backend + libsql
  -> owner data, alerts, geofences, admin registration
Android owner app
```

## Firmware

Primary source:

- [`main/ble_tracker.c`](../main/ble_tracker.c)

Current key constants:

| Symbol | Value |
|--------|-------|
| `DEVICE_ID` | `0x0000AB01` |
| `MANUFACTURER_ID` | `0xFF01` |
| `PIN_REED_SWITCH` | `GPIO_NUM_5` |
| `REED_SWITCH_IS_NC` | `0` |
| `HEARTBEAT_INTERVAL_SEC` | `6` |
| `SELF_TEST_INTERVAL_SEC` | `86400` |

Packet types:

| Code | Name |
|------|------|
| `0` | `HEARTBEAT` |
| `1` | `STATE_CHANGE` |
| `2` | `SELF_TEST` |

Health bits:

| Bit | Meaning |
|-----|---------|
| `0` | reed fault |
| `1` | boot contradiction |
| `2` | ADC fault |

Behavior:

- The tracker broadcasts manufacturer-specific data with a 10-byte payload.
- On state change it sends a burst of 5 advertisements with 2-second spacing.
- Battery sensing is currently not live; the firmware advertises `BATT_GOOD` as a fixed external-power state.
- NVS stores last-known state and `days_since_change`.

Current configured target from [`sdkconfig`](../sdkconfig):

- `esp32c3`

## BLE Payload

The Android parser and backend both assume this 10-byte payload after the manufacturer ID:

| Offset | Size | Field |
|--------|------|-------|
| `0` | `4` | `device_id` little-endian |
| `4` | `1` | `bag_state` |
| `5` | `1` | `battery_level` |
| `6` | `1` | `seq_num` |
| `7` | `1` | `packet_type` |
| `8` | `1` | `health_status` |
| `9` | `1` | `days_since_change` |

Manufacturer ID:

- `0xFF01`

## Android App

Important files:

- [`android-app/app/src/main/java/com/bletracker/app/data/BackendApi.kt`](../android-app/app/src/main/java/com/bletracker/app/data/BackendApi.kt)
- [`android-app/app/src/main/java/com/bletracker/app/data/Prefs.kt`](../android-app/app/src/main/java/com/bletracker/app/data/Prefs.kt)
- [`android-app/app/src/main/java/com/bletracker/app/scanner/BleScanService.kt`](../android-app/app/src/main/java/com/bletracker/app/scanner/BleScanService.kt)
- [`android-app/app/src/main/java/com/bletracker/app/scanner/BlePacketParser.kt`](../android-app/app/src/main/java/com/bletracker/app/scanner/BlePacketParser.kt)

Current Android config:

| Setting | Value |
|---------|-------|
| `compileSdk` | `35` |
| `targetSdk` | `35` |
| `minSdk` | `26` |
| Java target | `17` |

Current roles:

- owner
- scanner relay
- admin provisioning client

Operational behavior:

- foreground BLE scan with manufacturer filter `0xFF01`
- duplicate suppression in-app for 2 seconds on `deviceId + seqNum + packetType`
- upload queue flushed every 10 seconds, up to 25 items per batch
- owner alert polling every 5 seconds while authenticated
- optional auto-start after reboot

Current default backend URL:

- `https://ble-smart-luggage-tracker.onrender.com`

Manifest note:

- `android:usesCleartextTraffic="true"` is still enabled for local HTTP testing

## Backend

Primary sources:

- [`backend/src/server.js`](../backend/src/server.js)
- [`backend/src/logic.js`](../backend/src/logic.js)
- [`backend/src/store.js`](../backend/src/store.js)
- [`backend/src/security.js`](../backend/src/security.js)

Runtime:

- Node.js `>=20`
- built-in `http` server
- libsql persistence via `@libsql/client`

Persistence behavior:

- if `TURSO_DATABASE_URL` is set, the backend uses that libsql endpoint
- otherwise it defaults to `file:./data/ble-tracker.db`
- the app state is stored as one JSON document in a single table row

Background jobs:

- `evaluateBackgroundAlerts` runs every 5 seconds

Security behavior:

- bearer-token authentication
- 30-day session expiry
- one active session per user
- password hashing with `scrypt`
- manual claim codes stored as hashes
- in-memory rate limiting at 120 requests per minute per `IP + path`

## API Surface

Authentication:

- `POST /api/users`
- `POST /api/auth/login`
- `POST /api/auth/google`
- `GET /api/me`
- `GET /api/bootstrap`

Devices:

- `POST /api/devices/register`
- `GET /api/devices`
- `DELETE /api/devices/:deviceId`
- `POST /api/devices/:deviceId/monitoring`

Scanner ingestion:

- `POST /api/sightings`

Alerts and history:

- `GET /api/events`
- `GET /api/alerts`
- `POST /api/alerts/ack`
- `GET /api/notifications`

Geofences:

- `GET /api/geofences`
- `POST /api/geofences`
- `DELETE /api/geofences/:geofenceId`

Admin and operations:

- `POST /api/admin/device-registrations`
- `GET /api/health`

## Alert Logic

Realtime alerts from new sightings:

- `BAG_OPENED`
- `BAG_CLOSED`
- `STATE_CHANGE_SIGNAL`
- `SELF_TEST_HEALTH`
- `GEOFENCE_EXIT`
- `GEOFENCE_ENTRY`

Background alert:

- `PROXIMITY_LOST`

Recovery alert:

- `PROXIMITY_RESTORED`

Key proximity rule:

- a claimed device with `lastSeenAt` older than `ALERT_PROXIMITY_STALE_MS` raises `PROXIMITY_LOST`

## Data Shapes

Device records currently include:

- `deviceId`
- `ownerUserId`
- `displayName`
- `lastSeenAt`
- `lastLocation`
- `lastRssi`
- `lastPacket`
- `lastSequence`
- `lastState`
- `geofenceState`
- `status`
- `proximityMonitoringEnabled`
- `proximityAlertSentForSeenAt`

Geofence records currently include:

- `id`
- `userId`
- `name`
- `center`
- `radiusMeters`
- `enabled`
- `createdAt`

## Configuration References

Backend environment:

- [`backend/.env.example`](../backend/.env.example)

Android backend default:

- [`android-app/app/src/main/java/com/bletracker/app/data/Prefs.kt`](../android-app/app/src/main/java/com/bletracker/app/data/Prefs.kt)

Deployment blueprint:

- [`render.yaml`](../render.yaml)
