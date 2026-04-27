# User Documentation — BLE Smart Luggage Tracker

---

## Table of Contents

1. [What This System Does](#1-what-this-system-does)
2. [What You Need](#2-what-you-need)
3. [Setting Up the Tracker Hardware](#3-setting-up-the-tracker-hardware)
4. [Installing the Android App](#4-installing-the-android-app)
5. [Creating an Owner Account](#5-creating-an-owner-account)
6. [Claiming Your Tracker](#6-claiming-your-tracker)
7. [Using the Owner Dashboard](#7-using-the-owner-dashboard)
8. [Understanding Alerts](#8-understanding-alerts)
9. [Setting Up Geofences](#9-setting-up-geofences)
10. [Using a Phone as a Scanner (Relay)](#10-using-a-phone-as-a-scanner-relay)
11. [Frequently Asked Questions](#11-frequently-asked-questions)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. What This System Does

The BLE Smart Luggage Tracker is a small device that attaches to or is placed inside your bag. It broadcasts a wireless signal (Bluetooth Low Energy) that nearby phones can detect. Those phones relay information to a cloud server so you can:

- **Know if your bag was opened** — the tracker has a magnetic sensor (reed switch) that detects when the bag is unzipped or opened.
- **See where your bag was last spotted** — any phone running the app that passes near your bag reports its GPS location.
- **Get notified when your bag goes out of range** — if no phone has seen your tracker for more than a minute, you receive a proximity lost alert.
- **Define a safe zone (geofence)** — if your bag leaves a designated area (e.g. your home, hotel room), you receive an alert.
- **Check battery status** — the tracker reports its battery level in every packet.

---

## 2. What You Need

| Item | Purpose |
|------|---------|
| BLE Tracker device (ESP32-based) | Attaches to your bag |
| Android phone (Android 8 or higher) | Owner app and/or scanner |
| Internet connection | Upload sightings to the backend |
| Claim code | Provided by the system administrator when the device is registered |

---

## 3. Setting Up the Tracker Hardware

The tracker device is pre-flashed and ready to use. To set it up:

1. **Insert a battery** into the tracker. The device starts automatically — there is no power button.
2. **Attach the tracker to your bag.** The magnetic reed switch must be positioned so that the magnet (glued to the opposite side of the zipper or flap) closes the switch when the bag is shut.
3. **Verify the tracker is broadcasting** — if you have the app installed and scanning is enabled, you should see your device appear within a few seconds of being in range.

> **Note:** The tracker broadcasts a heartbeat signal every 6 seconds. You do not need to do anything to keep it active.

---

## 4. Installing the Android App

The app is distributed as an APK file. To install it:

1. On your Android phone, go to **Settings → Security** (or **Apps → Special app access**) and enable **Install unknown apps** for your file manager or browser.
2. Download or transfer the APK to your phone.
3. Tap the APK file to install it.
4. Open the app — it is called **BLE Tracker**.

> **Tip:** If your organisation distributes the app through Google Play, search for "BLE Tracker" in the Play Store instead.

---

## 5. Creating an Owner Account

1. Open the **BLE Tracker** app.
2. Tap **Create Account**.
3. Enter your name, email address, and a password (at least 6 characters).
4. Tap **Register**. You are automatically signed in.

Alternatively, tap **Sign in with Google** to use your Google account.

> **Forgot your password?** Contact your system administrator to reset your account.

---

## 6. Claiming Your Tracker

Before you can monitor a tracker, you must claim it using a one-time code provided by the administrator.

1. Open the app and sign in.
2. Tap **Add Tracker** (or the **+** button on the devices screen).
3. Enter the **Device ID** (e.g. `0x0000AB01`) — this is printed on your tracker device or provided by the administrator.
4. Enter the **Manual Code** (e.g. `ABCD1234`) — this is the one-time claim code from the administrator.
5. Enter a friendly name for your bag (e.g. "Blue Travel Bag").
6. Tap **Claim**. The tracker now appears in your device list.

---

## 7. Using the Owner Dashboard

After signing in and claiming a tracker, the main screen shows:

### Device Card

Each claimed tracker shows:

| Information | Description |
|------------|-------------|
| **Name** | The friendly name you gave the device |
| **Bag state** | Open or Closed |
| **Last seen** | How long ago a phone detected the tracker |
| **Battery** | Critical / Low / Medium / Good |
| **Location** | Approximate GPS coordinates where the tracker was last spotted |
| **Geofence state** | Inside / Outside / Unknown |
| **Proximity monitoring** | Toggle on/off from device settings |

### Refreshing

The app automatically refreshes device information every few seconds when the screen is active. Pull down on the device list to refresh manually.

### Device Settings

Tap a device card and then the settings icon to:

- **Rename** the device.
- **Toggle proximity monitoring** on or off.
- **Remove** (unclaim) the device.

---

## 8. Understanding Alerts

Alerts appear on the **Alerts** screen (bell icon). Each alert has a type, a message, and a timestamp.

### Alert Types

| Alert | What It Means | What To Do |
|-------|--------------|-----------|
| **BAG_OPENED** | The bag was detected open | Check if you opened it; if not, investigate |
| **BAG_CLOSED** | The bag was detected closed | Informational |
| **PROXIMITY_LOST** | No phone has seen the tracker for > 1 minute | Move closer to the bag or check nearby scanners |
| **GEOFENCE_EXIT** | The bag left a defined safe zone | The bag may have been moved |
| **HEALTH_FAULT** | The tracker detected a hardware fault | Check battery; contact support if fault persists |

### Acknowledging Alerts

Tap an alert and then **Acknowledge** to mark it as seen. Acknowledged alerts are moved out of the active list but remain in history.

---

## 9. Setting Up Geofences

A geofence is a circular safe zone on the map. If your bag leaves the zone, you get an alert.

### Creating a Geofence

1. Go to the **Geofences** screen.
2. Tap **Add Geofence**.
3. Enter a name (e.g. "Home").
4. Enter the latitude and longitude of the centre point, or tap the map to set it.
5. Enter the radius in metres (e.g. `100` for a 100 m radius).
6. Tap **Save**.

### Enabling / Disabling

Each geofence has a toggle. Disable it temporarily if you are travelling and do not want alerts while you move.

### Deleting a Geofence

Swipe the geofence left or tap the delete icon to remove it permanently.

---

## 10. Using a Phone as a Scanner (Relay)

Any Android phone running the app can act as a scanner — it does not need an owner account. Scanner phones detect nearby trackers and upload sightings to the backend, expanding the network coverage.

### Enabling Scanner Mode

1. Open the app.
2. Go to **Settings** (gear icon).
3. Toggle **Enable BLE Scanning** to on.
4. Grant the requested permissions:
   - **Nearby devices** (Bluetooth scanning)
   - **Location** (required for BLE scanning and GPS tagging)
   - **Notifications** (for the persistent scanning notification)

The scanning service runs in the foreground — a persistent notification appears in the notification shade while scanning is active. This is required by Android to keep the scan alive in the background.

### Auto-Start After Reboot

In **Settings**, enable **Start scanning automatically after reboot** so the scanner service resumes without manual action after the phone restarts.

### Changing the Backend URL

If your organisation uses a private backend, go to **Settings → Backend URL** and enter the full HTTPS address (e.g. `https://tracker.yourcompany.com`).

---

## 11. Frequently Asked Questions

**Q: How far away can the tracker be detected?**  
A: Typical Bluetooth Low Energy range is 10–50 metres in open space. Walls, metal, and body mass reduce range. A RSSI value of -70 dBm or better indicates a strong nearby signal; -90 dBm or worse indicates the device is at the edge of range.

**Q: Does the tracker need Wi-Fi or mobile data?**  
A: No. The tracker only uses Bluetooth and has no internet connection of its own. A nearby phone with mobile data relays the information.

**Q: How long does the tracker battery last?**  
A: This depends on the specific hardware and battery size. With the default 6-second heartbeat interval, an ESP32 in active mode draws several milliamps continuously. Enabling deep sleep between advertisements would significantly extend battery life but requires firmware modification.

**Q: Can multiple phones scan simultaneously?**  
A: Yes. Any number of phones can scan and upload sightings. The backend deduplicates by sequence number.

**Q: What happens if the phone running the scanner has no internet?**  
A: Sightings are queued locally on the phone and retried automatically every 10 seconds.

**Q: Can I have multiple trackers?**  
A: Yes. Claim each tracker separately with its own device ID and manual code. All claimed trackers appear on the devices screen.

**Q: Is my location data private?**  
A: Scanner phones upload GPS coordinates along with sightings. This data is stored on the backend server and is visible only to the owner of the device associated with the sighting. Use a self-hosted backend for maximum privacy.

---

## 12. Troubleshooting

### App says "Cannot connect to backend"

- Confirm your phone has internet access.
- Go to **Settings → Backend URL** and verify the URL is correct and starts with `https://`.
- Check that the backend server is running by visiting `https://your-backend-url/api/health` in a browser. You should see `{"ok":true,…}`.

### Tracker not appearing in the device list

- Make sure you have claimed the tracker (see Section 6).
- Ensure a phone with scanning enabled is physically near the tracker (within ~10 m).
- Check that the scanner phone has Bluetooth on and location permission granted.
- Check the backend health endpoint to confirm the backend is reachable.

### Proximity lost alert keeps appearing

- The alert fires when no phone has seen the tracker for more than 60 seconds.
- Walk closer to the bag with your phone's scanner active.
- If you do not need proximity monitoring, disable it in device settings.

### Bag state shows "open" when the bag is closed

- The reed switch may need repositioning. The magnet must come close enough to the switch to close it when the bag is shut.
- Check the firmware's `REED_SWITCH_IS_NC` setting — if your switch is normally-closed, set it to `1`.

### Geofence alerts not firing

- Verify the geofence is enabled (toggle is on).
- Confirm that sightings are being uploaded with valid GPS coordinates. The geofence check requires a location in the sighting.
- The scanner phone must have location permission and a GPS fix.

### Battery shows "CRITICAL"

- Replace or recharge the tracker battery immediately.
- The tracker continues to broadcast at reduced capacity but may stop if the voltage drops too low.
