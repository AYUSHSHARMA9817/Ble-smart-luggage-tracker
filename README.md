# BLE Tracker

BLE Tracker is a smart bag monitoring system built around BLE advertisements, Android relay phones, and an owner dashboard. It helps owners understand whether a tracked bag is open or closed, when it was last seen, how near it likely is, where a nearby scanner detected it, and when important events require attention.

## Quick Start

1. Run or deploy the backend from [`backend/`](./backend)
2. Point the Android app to the backend URL
3. Install the app on the owner phone and any relay phones
4. Claim a tracker, enable scanning, and start receiving sightings and alerts

## What It Does

- Detects a tracker through BLE advertisements
- Shows live bag state such as open or closed
- Records when and where the tracker was last seen
- Estimates proximity using RSSI signal strength
- Raises alerts for bag opening, bag closing, geofence exit, geofence return, and proximity loss/restoration
- Allows nearby phones to relay sightings back to the owner backend

## Project Structure

- [`main/`](./main): ESP32 tracker firmware
- [`android-app/`](./android-app): Android scanner and owner app
- [`backend/`](./backend): Node.js backend for auth, sightings, geofences, Turso persistence, and alerts
- [`docs/`](./docs): integration notes and public assets

## Demo

Add screenshots or GIFs in [`docs/assets/`](./docs/assets) and link them here.

Suggested assets:

- owner login screen
- owner dashboard
- alerts screen
- scanner flow demo GIF

Example section to add later:

```md
![Owner Dashboard](docs/assets/owner-dashboard.png)
![Alerts Screen](docs/assets/alerts-screen.png)
```

## Deploy

Deploy the backend only. The Android app connects to the deployed backend URL from its settings screen or the configured default value.

Included deployment support:

- root [.gitignore](./.gitignore)
- backend [Dockerfile](./backend/Dockerfile)
- backend [Procfile](./backend/Procfile)
- backend env template: [backend/.env.example](./backend/.env.example)
- Render blueprint: [render.yaml](./render.yaml)
- Turso JSON import helper: [backend/scripts/import-json-to-turso.mjs](./backend/scripts/import-json-to-turso.mjs)

## Data Strategy

Production backend state lives in Turso. The checked-in JSON files are only sample or migration data.

- sample database export: [backend/data/app.sample.json](./backend/data/app.sample.json)
- import helper: `cd backend && npm run db:import-json`

## More Details

- [backend/README.md](./backend/README.md)
- [android-app/README.md](./android-app/README.md)
- [docs/android_integration.md](./docs/android_integration.md)

## License

[MIT](./LICENSE)
