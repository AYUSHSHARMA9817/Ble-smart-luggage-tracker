# Technical Documentation — BLE Smart Luggage Tracker

---

## Table of Contents

1. [System Architecture](#1-system-architecture)
2. [ESP32 Firmware](#2-esp32-firmware)
3. [Backend Server](#3-backend-server)
4. [Android Application](#4-android-application)
5. [BLE Packet Protocol](#5-ble-packet-protocol)
6. [REST API Reference](#6-rest-api-reference)
7. [Data Model](#7-data-model)
8. [Security Design](#8-security-design)
9. [Alert Logic](#9-alert-logic)
10. [Configuration Reference](#10-configuration-reference)

---

## 1. System Architecture

```
┌─────────────────┐        BLE advertisement        ┌─────────────────────┐
│  ESP32 Tracker  │ ──────────────────────────────> │  Android Scanner    │
│  (on the bag)   │                                  │  (relay phone)      │
└─────────────────┘                                  └────────┬────────────┘
                                                              │  POST /api/sightings
                                                              │  (HTTPS)
                                                     ┌────────▼────────────┐
                                                     │  Node.js Backend    │
                                                     │  (cloud server)     │
                                                     └────────┬────────────┘
                                                              │  GET /api/bootstrap
                                                              │  GET /api/alerts
                                                     ┌────────▼────────────┐
                                                     │  Android Owner App  │
                                                     │  (owner phone)      │
                                                     └─────────────────────┘
```

### Data Flow Summary

1. The **ESP32 tracker** broadcasts a 10-byte BLE manufacturer-specific advertisement every 6 seconds (heartbeat), on every reed-switch state change (×5 burst), and once per day (self-test).
2. Any nearby **Android phone running the scanner role** picks up the advertisement, parses the payload, attaches GPS coordinates if available, and POSTs a sighting to the backend.
3. The **backend** stores the sighting, updates device state, evaluates geofences, and generates alerts for significant events.
4. The **owner's Android app** polls the backend for alerts and displays device state, proximity, and event history.

---

## 2. ESP32 Firmware

**Source:** `main/ble_tracker.c`  
**Build system:** ESP-IDF with CMake (`CMakeLists.txt`)  
**BLE stack:** Apache NimBLE

### Key Constants

| Symbol | Value | Meaning |
|--------|-------|---------|
| `DEVICE_ID` | `0x0000AB01` | 32-bit device identifier, unique per unit |
| `MANUFACTURER_ID` | `0xFF01` | 16-bit company ID embedded in every advertisement |
| `HEARTBEAT_INTERVAL_SEC` | `6` | Seconds between periodic heartbeat packets |
| `SELF_TEST_INTERVAL_SEC` | `86400` | Seconds between daily self-test packets (24 h) |
| `PIN_REED_SWITCH` | `GPIO_NUM_5` | GPIO pin connected to the reed switch |

### Reed Switch Wiring

- The reed switch is wired between GPIO 5 and GND.
- The internal pull-up resistor is enabled, so the line reads HIGH when the switch is open (bag open) and LOW when the magnet is near (bag closed).
- `REED_SWITCH_IS_NC` can be set to `1` to invert this logic for normally-closed reed switches.

### Packet Types

| Code | Name | Trigger |
|------|------|---------|
| `0x00` | `HEARTBEAT` | Every `HEARTBEAT_INTERVAL_SEC` seconds |
| `0x01` | `STATE_CHANGE` | Reed-switch transition; sent as a burst of 5 packets |
| `0x02` | `SELF_TEST` | Every `SELF_TEST_INTERVAL_SEC` seconds |

### Health Status Bitmask

| Bit | Symbol | Meaning |
|-----|--------|---------|
| 0 | `HEALTH_BIT_REED_FAULT` | Reed switch read failed |
| 1 | `HEALTH_BIT_BOOT_CONTRADICT` | Bag state at boot contradicts last-saved NVS state |
| 2 | `HEALTH_BIT_ADC_FAULT` | Battery ADC read failed |

### NVS Persistence

The firmware stores the last-known `bag_state` and `days_since_change` counter in NVS so they survive device resets. On boot, if the live reed-switch reading disagrees with the stored state, bit 1 (`HEALTH_BIT_BOOT_CONTRADICT`) is set in the next packet's health status field.

### Flashing

```bash
# Configure target
idf.py set-target esp32

# Build
idf.py build

# Flash and monitor (replace /dev/ttyUSB0 with your port)
idf.py -p /dev/ttyUSB0 flash monitor
```

---

## 3. Backend Server

**Source:** `backend/src/`  
**Runtime:** Node.js ≥ 20 (ES Modules)  
**Database:** JSON flat file (`backend/data/app.json`)  
**Port:** 8787 (configurable via `PORT` env var)

### Source Files

| File | Responsibility |
|------|---------------|
| `server.js` | HTTP server, route dispatching, background alert job |
| `logic.js` | BLE packet parsing, sighting processing, alert evaluation, geofence checks |
| `security.js` | Password hashing (scrypt), session tokens, rate limiting, security event logging |
| `store.js` | Atomic JSON file read/write, schema migration, ID generation |

### Background Job

A `setInterval` fires every 5 seconds. It loads the database, calls `evaluateBackgroundAlerts` for every owned device, and saves the database. This catches devices that have gone silent without sending a state-change packet (e.g. battery died or moved out of range) and raises a `PROXIMITY_LOST` alert.

### Static Files

The server also serves `backend/public/index.html` as a basic read-only dashboard. The dashboard fetches `/api/health`, `/api/alerts`, and `/api/devices` every 4 seconds.

### Rate Limiting

Every API path is limited to **120 requests per minute per IP**. Exceeding this returns HTTP 429. Rate-limit counters are in-memory and reset when the server restarts.

---

## 4. Android Application

**Source:** `android-app/`  
**Language:** Kotlin  
**Min SDK:** Android 8 (API 26)  
**BLE API:** Android BluetoothLeScanner

### Package Structure

```
com.bletracker.app
├── BleTrackerApplication.kt      Application class
├── MainActivity.kt               Single-activity entry point
├── MainViewModel.kt              UI state, backend polling
├── auth/
│   └── GoogleSignInManager.kt    Optional Google OAuth sign-in
├── data/
│   ├── BackendApi.kt             HTTP client (no external libraries)
│   ├── Models.kt                 Data transfer objects (DTOs)
│   └── Prefs.kt                  SharedPreferences wrapper
└── scanner/
    ├── BlePacketParser.kt        Parses raw manufacturer payload bytes
    ├── BleScanService.kt         Foreground service that runs the BLE scan loop
    ├── BootCompletedReceiver.kt  Restarts scan service after device reboot
    ├── LocationSnapshot.kt       Best-effort GPS snapshot for sighting payloads
    ├── NotificationFactory.kt    Builds Android notification objects
    ├── OwnerAlertPoller.kt       Periodic owner alert polling from the backend
    └── SightingQueueStore.kt     Persists undelivered sightings to SharedPreferences
```

### Roles

The app operates in two roles simultaneously on the same phone:

- **Scanner role** — runs `BleScanService` in the foreground, scans for BLE advertisements with manufacturer ID `0xFF01`, parses each packet, and uploads a sighting payload to `/api/sightings`. If upload fails, the sighting is queued and retried every 10 seconds.
- **Owner role** — the user logs in, calls `/api/bootstrap` once to load devices, alerts, geofences, and then polls `/api/alerts` periodically for updates.

### BLE Scan Flow

1. User enables scanning in the app UI.
2. `BleScanService` starts as a foreground service with a persistent notification.
3. `BluetoothLeScanner` scans continuously with a manufacturer-data filter for `0xFF01`.
4. Each result is passed to `BlePacketParser` which validates and decodes the 10-byte payload.
5. `LocationSnapshot` attaches the most recent GPS fix (if permission is granted).
6. `SightingUploader` POSTs the assembled payload to the backend.
7. On network failure the sighting is saved to `SightingQueueStore` and retried by a background coroutine.

### Permissions

| Permission | Reason |
|-----------|--------|
| `BLUETOOTH_SCAN` | Start BLE scan |
| `BLUETOOTH_CONNECT` | Required by Android 12+ |
| `ACCESS_FINE_LOCATION` | Required for BLE scanning on API 23–30; optional GPS fix |
| `FOREGROUND_SERVICE` | Run scan service in the foreground |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14 foreground service type |
| `POST_NOTIFICATIONS` | Show scan and alert notifications |
| `RECEIVE_BOOT_COMPLETED` | Auto-start scanner after reboot |
| `WAKE_LOCK` | Keep CPU awake during active scan |

### Backend URL

The default backend URL is stored in `Prefs.DEFAULT_BACKEND_URL`. Users can override it in the app's Settings screen at any time. The current default is:

```
https://ble-tracker-backend.onrender.com
```

---

## 5. BLE Packet Protocol

### Advertisement Format

The tracker uses a **Manufacturer Specific** advertisement data record:

| Bytes | Field | Type | Notes |
|-------|-------|------|-------|
| 0–1 | Manufacturer ID | `uint16_t` LE | Always `0xFF01` |
| 2–5 | Device ID | `uint32_t` LE | Unique per unit, e.g. `0x0000AB01` |
| 6 | Bag state | `uint8_t` | `0` = closed, `1` = open |
| 7 | Battery level | `uint8_t` | `0`=CRITICAL, `1`=LOW, `2`=MEDIUM, `3`=GOOD |
| 8 | Sequence number | `uint8_t` | Wraps 255 → 0; used for duplicate detection |
| 9 | Packet type | `uint8_t` | `0`=HEARTBEAT, `1`=STATE_CHANGE, `2`=SELF_TEST |
| 10 | Health status | `uint8_t` | Bitmask of fault flags |
| 11 | Days since change | `uint8_t` | Capped at 255 |

Total payload: **10 bytes** (bytes 2–11; bytes 0–1 are the manufacturer ID consumed by the BLE stack).

### Duplicate Suppression

The backend compares the incoming `seqNum` to the last stored value. A packet is considered a duplicate if the `seqNum` and `packetType` are unchanged since the last accepted sighting for that device.

---

## 6. REST API Reference

Base URL: `https://ble-tracker-backend.onrender.com`

All authenticated endpoints require the header:

```
Authorization: Bearer <authToken>
```

---

### Authentication

#### `POST /api/users`
Create a new user account.

**Request body:**
```json
{
  "name": "Ayush Sharma",
  "email": "ayush@example.com",
  "password": "secret123"
}
```

**Response `201`:**
```json
{
  "user": { "id": "user_…", "name": "Ayush Sharma", "email": "ayush@example.com" },
  "authToken": "<token>",
  "expiresAt": "2026-05-27T…"
}
```

---

#### `POST /api/auth/login`
Log in with email and password.

**Request body:**
```json
{ "email": "ayush@example.com", "password": "secret123" }
```

**Response `200`:** Same shape as `/api/users`.

---

#### `POST /api/auth/google`
Exchange a Google ID token for a backend session token.

**Request body:**
```json
{ "idToken": "<google id token>", "serverClientId": "<optional web client id>" }
```

---

#### `GET /api/me`
Return the authenticated user's profile.

---

### Bootstrap

#### `GET /api/bootstrap`
Return the full initial payload for the authenticated owner in one request.

**Response `200`:**
```json
{
  "user": { … },
  "devices": [ … ],
  "alerts": [ … ],
  "notifications": [ … ],
  "geofences": [ … ],
  "serverTime": "2026-04-27T15:00:00.000Z"
}
```

---

### Devices

#### `GET /api/devices`
Return all devices owned by the authenticated user.

#### `POST /api/devices/register`
Claim a pre-registered device.

**Request body:**
```json
{
  "deviceId": "0x0000AB01",
  "manualCode": "ABCD1234",
  "displayName": "Blue Bag"
}
```

#### `DELETE /api/devices/:deviceId`
Unclaim a device. Removes all related alerts and notifications.

#### `POST /api/devices/:deviceId/monitoring`
Enable or disable proximity monitoring.

**Request body:** `{ "enabled": true }`

---

### Sightings

#### `POST /api/sightings`
Upload a BLE sighting from a scanner phone. Does **not** require authentication — any scanner can upload.

**Request body:**
```json
{
  "scannerUserId": "scanner_android_1",
  "manufacturerId": "0xFF01",
  "deviceId": "0x0000AB01",
  "rssi": -68,
  "location": { "lat": 26.144, "lng": 91.736, "accuracyMeters": 18 },
  "packet": {
    "bagState": 1,
    "batteryLevel": 2,
    "seqNum": 17,
    "packetType": 1,
    "healthStatus": 0,
    "daysSinceChange": 0
  }
}
```

**Response `201`:** Updated device record.

---

### Alerts

#### `GET /api/alerts`
Return alerts for the authenticated owner. Query params: `since=<ISO8601>`, `openOnly=true`.

#### `POST /api/alerts/ack`
Acknowledge an open alert.

**Request body:** `{ "alertId": "alert_…" }`

---

### Geofences

#### `GET /api/geofences`
Return all geofences for the authenticated user.

#### `POST /api/geofences`
Create a geofence.

**Request body:**
```json
{
  "name": "Home",
  "center": { "lat": 26.144, "lng": 91.736 },
  "radiusMeters": 100,
  "enabled": true
}
```

#### `DELETE /api/geofences/:geofenceId`
Delete a geofence.

---

### Events

#### `GET /api/events`
Return event history. Optional query param: `deviceId=<deviceId>`.

---

### Admin

#### `POST /api/admin/device-registrations`
Pre-register a tracker device and issue a one-time claim code.

**Request body:**
```json
{
  "adminSecret": "YOUR_ADMIN_SECRET",
  "deviceId": "0x0000AB01",
  "manualCode": "ABCD1234",
  "note": "demo unit"
}
```

---

### Health

#### `GET /api/health`
Returns basic server metrics (no auth required).

---

## 7. Data Model

The database is a single JSON file with the following top-level arrays:

| Collection | Description |
|-----------|-------------|
| `users` | Registered owner accounts |
| `sessions` | Active auth sessions (one per user) |
| `devices` | Tracker devices with latest state |
| `deviceRegistrations` | Admin-issued registration codes |
| `geofences` | Owner-defined geographic boundaries |
| `events` | Immutable event log (device registered, removed, etc.) |
| `alerts` | Generated alerts (`open`, `acknowledged`, `closed`) |
| `notifications` | Push-notification records linked to alerts |
| `scannerSightings` | Raw sighting upload log |
| `securityEvents` | Login attempts, rate limit hits, admin actions |

### Device Record Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique record ID |
| `deviceId` | string | Normalised hex device ID, e.g. `"0x0000AB01"` |
| `ownerUserId` | string\|null | ID of the claiming user |
| `displayName` | string | Human-readable label |
| `lastSeenAt` | ISO8601\|null | Timestamp of most recent accepted sighting |
| `lastLocation` | object\|null | `{ lat, lng, accuracyMeters }` |
| `lastRssi` | number\|null | Signal strength of most recent sighting |
| `lastPacket` | object\|null | Decoded packet from most recent sighting |
| `geofenceState` | string | `"inside"`, `"outside"`, or `"unknown"` |
| `status` | string | `"claimed"` or `"unclaimed"` |
| `proximityMonitoringEnabled` | boolean | Whether proximity loss alerts are active |

---

## 8. Security Design

### Password Hashing

User passwords are hashed with **scrypt** using a 16-byte random salt and parameters `N=16384, r=8, p=1`. Only the hash and salt are stored.

### Session Tokens

Sessions use a 32-byte cryptographically random token encoded as base64url. Only a SHA-256 hash of the token is stored in the database; the plain token is never persisted.

Sessions expire after **30 days**.

### Rate Limiting

All API paths are rate-limited to **120 requests per 60-second window per IP + path combination**. Exceeded requests receive HTTP 429.

### Admin Secret

The `/api/admin/device-registrations` endpoint requires a matching `adminSecret` field equal to the `ADMIN_REGISTRATION_SECRET` environment variable. This is never exposed via any other endpoint.

### Security Event Log

All significant security events are appended to `db.securityEvents`:

- `LOCAL_LOGIN_SUCCESS` / `LOCAL_LOGIN_FAILED`
- `GOOGLE_LOGIN_SUCCESS`
- `MANUAL_USER_CREATED`
- `LOCAL_PASSWORD_SET`
- `DEVICE_REGISTERED` / `DEVICE_REGISTRATION_UNKNOWN_DEVICE` / `DEVICE_REGISTRATION_ALREADY_CLAIMED` / `DEVICE_REGISTRATION_CODE_INVALID`
- `ADMIN_REGISTRATION_DENIED` / `REGISTRATION_CODE_CREATED`
- `RATE_LIMIT_HIT`

---

## 9. Alert Logic

Alerts are generated by `logic.js` in two places:

### On Sighting (`recordSighting`)

| Alert Type | Condition |
|------------|-----------|
| `BAG_OPENED` | `bagState` transitions from `0` to `1` on a `STATE_CHANGE` packet |
| `BAG_CLOSED` | `bagState` transitions from `1` to `0` on a `STATE_CHANGE` packet |
| `GEOFENCE_EXIT` | Device location moves outside all active geofences when it was previously inside |
| `HEALTH_FAULT` | `healthStatus` bitmask is non-zero on a `SELF_TEST` packet |

### Background (`evaluateBackgroundAlerts`)

| Alert Type | Condition |
|------------|-----------|
| `PROXIMITY_LOST` | Device has not been seen for > 60 seconds and `proximityMonitoringEnabled` is `true` |

### Alert Status Lifecycle

```
open → acknowledged → (stays acknowledged, not re-raised for same sighting)
open → closed        (PROXIMITY_LOST closes when device is seen again)
```

---

## 10. Configuration Reference

### Backend Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8787` | Server listen port |
| `DATA_PATH` | `data/app.json` | JSON database file path |
| `ADMIN_REGISTRATION_SECRET` | *(required)* | Secret for admin registration endpoint |

### Firmware Constants (`ble_tracker.c`)

| Symbol | Default | Description |
|--------|---------|-------------|
| `DEVICE_ID` | `0x0000AB01` | 32-bit device identifier |
| `MANUFACTURER_ID` | `0xFF01` | BLE manufacturer ID |
| `PIN_REED_SWITCH` | `GPIO_NUM_5` | Reed switch GPIO |
| `HEARTBEAT_INTERVAL_SEC` | `6` | Heartbeat period |
| `SELF_TEST_INTERVAL_SEC` | `86400` | Self-test period |

### Android App Defaults (`Prefs.kt`)

| Key | Default | Description |
|-----|---------|-------------|
| `backendBaseUrl` | `https://ble-tracker-backend.onrender.com` | Backend HTTPS URL |
| `scannerUserId` | `scanner_android_1` | Scanner identity sent with sightings |
| `scannerAutostartEnabled` | `false` | Auto-start scanner after reboot |
