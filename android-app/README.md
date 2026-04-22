# BLE Tracker Android App

This app has two roles:

- `Scanner`: scans for BLE advertisements from trackers and uploads sightings to the backend.
- `Owner`: logs in, claims trackers, manages geofences, and reviews alerts.

## What It Does

- Runs BLE scanning in a foreground service
- Filters advertisements by manufacturer ID `0xFF01`
- Parses tracker packets and uploads sightings to the backend
- Queues uploads locally if the network is unavailable
- Shows tracker state, RSSI-based proximity estimate, scanner location when available, alerts, and geofences

## Important Runtime Notes

- Background scanning requires a foreground service notification
- Scanner location is best-effort and depends on Android location permission plus an available device fix
- The app can resume scanning after reboot if enabled in settings

## Backend URL

The active backend URL is editable in the app under `Settings`.

For fresh installs, the default backend URL comes from:

- `app/src/main/java/com/bletracker/app/data/Prefs.kt`

## Build

```bash
cd android-app
./gradlew :app:assembleDebug
```

Use Java 11+ for Android Gradle Plugin compatibility.
