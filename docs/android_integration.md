# Android Integration Notes

## Permissions Model

For the Android app, the target approach is:

- Request `BLUETOOTH_SCAN`
- Request `BLUETOOTH_CONNECT`
- Request `ACCESS_FINE_LOCATION`
- Do not require always-on location by default

Use a foreground scanning service when the user explicitly enables tracker monitoring. This keeps the scan active in the background without designing the product around permanent background location access.

## Scan Pipeline

1. Start BLE scan in a foreground service.
2. Filter results by manufacturer ID `0xFF01`.
3. Decode:
   - `device_id`
   - `bag_state`
   - `battery_level` (used as external power-health status for the current power-bank hardware profile)
   - `seq_num`
   - `packet_type`
   - `health_status`
   - `days_since_change`
4. Attach phone location if available from the app at that moment.
5. Queue the discovery locally if upload fails.
6. Retry queued uploads every 10 seconds.
7. POST the discovery to `/api/sightings`.

## Server Responsibilities

The app should stay relatively thin. The backend should own:

- device ownership lookup
- sequence-based duplicate suppression
- previous-state vs current-state change detection
- tamper alert generation
- self-test health interpretation
- proximity loss evaluation
- proximity restoration evaluation
- geofence evaluation
- geofence exit and re-entry notifications
- event history
- notification fan-out

## Frontend Responsibilities

The frontend can be either:

- embedded in the Android app using backend APIs, or
- a web dashboard that reads the same backend APIs

The backend in this repo already includes a simple dashboard at `/`.
