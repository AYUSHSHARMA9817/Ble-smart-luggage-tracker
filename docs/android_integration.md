# Android Integration Notes

This note describes the Android app behavior that other parts of the system should assume.

## Roles

The current app combines three roles:

- owner client
- scanner relay
- admin provisioning client

## BLE Packet Contract

The scanner expects manufacturer ID `0xFF01` and a 10-byte payload:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4 | `device_id` little-endian |
| 4 | 1 | `bag_state` |
| 5 | 1 | `battery_level` |
| 6 | 1 | `seq_num` |
| 7 | 1 | `packet_type` |
| 8 | 1 | `health_status` |
| 9 | 1 | `days_since_change` |

## Scanner Flow

1. `BleScanService` starts as a foreground service.
2. Android scans for advertisements with manufacturer ID `0xFF01`.
3. `BlePacketParser` decodes the payload.
4. The app captures best-effort location if available.
5. The sighting is queued locally.
6. `SightingUploader` uploads queued sightings immediately and then every 10 seconds until the queue drains.

Current dedupe rule inside the app:

- suppress the same `deviceId + seqNum + packetType` for 2 seconds

## Owner Flow

1. The owner signs in.
2. The app calls `GET /api/bootstrap`.
3. The app reads owned devices, alerts, and geofences from that bootstrap response.
4. While authenticated, `OwnerAlertPoller` polls `GET /api/alerts?openOnly=true` every 5 seconds and creates local Android notifications for new open alerts.

## Admin Flow

The app can call:

- `POST /api/admin/device-registrations`

This is the same backend action used by `/admin.html`. The generated manual claim code is shown once and cached locally for the current app session history.

## Required Backend Endpoints

- `POST /api/users`
- `POST /api/auth/login`
- `POST /api/auth/google`
- `GET /api/bootstrap`
- `POST /api/devices/register`
- `DELETE /api/devices/:deviceId`
- `POST /api/devices/:deviceId/monitoring`
- `GET /api/geofences`
- `POST /api/geofences`
- `DELETE /api/geofences/:geofenceId`
- `GET /api/alerts`
- `POST /api/alerts/ack`
- `POST /api/sightings`
- `POST /api/admin/device-registrations`
- `GET /api/health`

## Permissions Model

The app declares:

- Bluetooth scan/connect permissions
- fine location
- notifications
- foreground-service permissions
- boot completed receiver permission

The scan service refuses to run if Bluetooth scan/connect permissions are missing.

## Operational Notes

- The backend URL is editable in the app settings.
- The default backend URL currently points at `https://ble-smart-luggage-tracker.onrender.com`.
- The manifest still allows cleartext traffic for local testing.
