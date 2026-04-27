# BLE Tracker Android App

The Android app currently supports three practical roles in one APK:

- `Owner`: sign in, claim trackers, view device state, acknowledge alerts, and manage geofences
- `Scanner`: run foreground BLE scanning and upload sightings
- `Admin`: generate one-time claim codes using the backend admin registration endpoint

## Platform Requirements

- Kotlin + Jetpack Compose
- Java `17`
- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 26`

Build:

```bash
cd android-app
./gradlew :app:assembleDebug
```

## Current Capabilities

- foreground BLE scan filtered to manufacturer ID `0xFF01`
- 10-byte tracker packet parsing
- best-effort location attachment to sightings
- persistent local upload queue with retry every 10 seconds
- owner bootstrap and alert refresh against the backend
- local Android notifications for newly opened alerts
- optional reboot auto-start for the scan service
- admin provisioning flow inside the app

## Key Runtime Behavior

- Scanning runs in [`BleScanService`](./app/src/main/java/com/bletracker/app/scanner/BleScanService.kt).
- The service is `START_STICKY` and posts a foreground notification.
- Duplicate advertisements are suppressed for 2 seconds by `deviceId + seqNum + packetType`.
- Queued sightings are uploaded in batches of up to `25`.
- Owner open alerts are polled every `5` seconds while an auth token is present.
- Boot auto-start is gated by the saved preference `scannerAutostartEnabled`.

## Permissions

Declared in [`app/src/main/AndroidManifest.xml`](./app/src/main/AndroidManifest.xml):

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `POST_NOTIFICATIONS`
- `RECEIVE_BOOT_COMPLETED`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `WAKE_LOCK`
- `BLUETOOTH` and `BLUETOOTH_ADMIN` for API 30 and below
- `ACCESS_FINE_LOCATION`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`

Notes:

- The scan service itself requires `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- Location is optional for upload quality, but it is still requested by the app and needed on older Android versions for BLE scanning behavior.

## Backend Configuration

The backend base URL is stored in [`Prefs.kt`](./app/src/main/java/com/bletracker/app/data/Prefs.kt).

Current default:

```kotlin
const val DEFAULT_BACKEND_URL = "https://ble-smart-luggage-tracker.onrender.com"
```

Users can override it from the Settings screen.

The manifest currently keeps:

```xml
android:usesCleartextTraffic="true"
```

This allows local HTTP testing. If you ship only against HTTPS, you can tighten that for release.

## Authentication

The app supports:

- email/password sign-up and sign-in
- optional Google sign-in

Google sign-in requires a configured web client ID in preferences and a backend that can reach Google's token verification endpoint.

## Important Source Files

- [`BackendApi.kt`](./app/src/main/java/com/bletracker/app/data/BackendApi.kt)
- [`Prefs.kt`](./app/src/main/java/com/bletracker/app/data/Prefs.kt)
- [`Models.kt`](./app/src/main/java/com/bletracker/app/data/Models.kt)
- [`BleScanService.kt`](./app/src/main/java/com/bletracker/app/scanner/BleScanService.kt)
- [`BlePacketParser.kt`](./app/src/main/java/com/bletracker/app/scanner/BlePacketParser.kt)
- [`SightingUploader.kt`](./app/src/main/java/com/bletracker/app/scanner/SightingUploader.kt)
- [`OwnerAlertPoller.kt`](./app/src/main/java/com/bletracker/app/scanner/OwnerAlertPoller.kt)
- [`BootCompletedReceiver.kt`](./app/src/main/java/com/bletracker/app/scanner/BootCompletedReceiver.kt)
