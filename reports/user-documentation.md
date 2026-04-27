# User Documentation — BLE Smart Luggage Tracker

This system uses a BLE tracker on the bag, Android phones that can scan for the tracker, and a backend that stores sightings and alerts.

## What You Can See

After a tracker is claimed to your account, the app can show:

- whether the bag is open or closed
- when it was last seen
- the latest scanner location, if a scanner phone had location available
- RSSI-based proximity estimate
- geofence state
- alert history

## What You Need

- a flashed tracker device
- at least one Android phone with the app installed
- a backend URL
- a one-time manual claim code from an admin

## Tracker Setup

1. Power the tracker.
2. Place or mount it so the reed switch and magnet line up when the bag closes.
3. Keep a scanner phone nearby to confirm packets are being seen.

The firmware currently emits:

- heartbeat packets every 6 seconds
- a burst of state-change packets when the reed switch changes
- a self-test packet every 24 hours

## Owner Setup

### Create an account

Use either:

- email and password
- Google sign-in, if configured in the app and backend

### Claim a tracker

1. Open the app.
2. Sign in as an owner.
3. Go to the device registration section.
4. Enter the tracker device ID, for example `0x0000AB01`.
5. Enter the one-time manual claim code from the admin.
6. Choose a display name.
7. Submit the registration.

## Scanner Setup

The same app can run as a scanner relay.

1. Open the app settings.
2. Enable background monitoring.
3. Grant Bluetooth permissions.
4. Grant location permission if you want location attached to sightings.
5. Optionally enable auto-start after reboot.

While scanning:

- the app runs a foreground service
- a persistent notification is shown
- sightings are queued locally if upload fails
- queued items retry every 10 seconds

## Admin Setup

Admins can create claim codes in three ways:

- the backend web page at `/admin.html`
- the backend API
- the app's admin mode

When a tracker is registered:

- the backend stores only a hashed claim code
- the plain code is returned once and should be given to the owner

## Alerts

Current backend alert types include:

- `BAG_OPENED`
- `BAG_CLOSED`
- `STATE_CHANGE_SIGNAL`
- `SELF_TEST_HEALTH`
- `GEOFENCE_EXIT`
- `GEOFENCE_ENTRY`
- `PROXIMITY_LOST`
- `PROXIMITY_RESTORED`

The app can acknowledge alerts. Open alerts are also polled by the scanner service every 5 seconds when the owner is signed in.

## Geofences

Owners can create geofences by entering:

- a name
- latitude
- longitude
- radius in meters

The app currently supports:

- creating geofences
- listing geofences
- deleting geofences

The backend also stores an `enabled` flag on geofences, but the current app UI does not expose a toggle for changing that flag after creation.

## Settings

The app lets you change:

- backend URL
- scanner reboot auto-start

The current default backend URL in code is:

```text
https://ble-smart-luggage-tracker.onrender.com
```

## Troubleshooting

### The app cannot reach the backend

- Confirm the backend URL in Settings.
- Open `/api/health` in a browser and confirm the server responds.
- If you use Google sign-in, confirm the backend can reach Google's token verification service.

### The tracker does not appear

- Make sure the tracker has been claimed by the owner.
- Make sure a scanner phone is nearby.
- Confirm Bluetooth permissions are granted.
- Confirm the tracker advertises manufacturer ID `0xFF01`.

### Location is missing

- The scanner can upload sightings without location.
- Grant fine location permission if you want coordinates attached.
- Make sure the phone has a recent location fix.

### Proximity alerts keep firing

- `PROXIMITY_LOST` is based on time since the last sighting.
- By default, the threshold is 60 seconds.
- If needed, pause device monitoring for that tracker from the app.

### The generated claim code is lost

- Create a new admin registration for the same device.
- That replaces the previous stored hashed code.
