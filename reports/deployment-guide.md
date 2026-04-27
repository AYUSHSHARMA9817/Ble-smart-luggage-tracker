# Deployment Guide — BLE Smart Luggage Tracker

This guide walks you through every step needed to make the backend publicly accessible on the internet and connect the Android app to it.

---

## Overview

The system has three components:

| Component | What it is | How it is deployed |
|-----------|-----------|-------------------|
| **ESP32 firmware** | C code running on the physical tracker | Flashed once to the hardware |
| **Backend** | Node.js server (auth, sightings, alerts) | Deployed to a cloud host |
| **Android app** | Scanner + owner app | Built as an APK and installed on phones |

Only the **backend** needs to be hosted publicly. The Android app connects to the backend URL over HTTPS, and the backend stores production data in Turso.

---

## Option A — Deploy to Render + Turso (recommended)

Render is the simplest option. The repository already includes a `render.yaml` blueprint and a `Dockerfile`.

### Step 1 — Create a Render account

Go to [https://render.com](https://render.com) and sign up with GitHub.

### Step 2 — Connect your GitHub repository

1. On the Render dashboard click **New → Blueprint**.
2. Select the `Ble-smart-luggage-tracker` repository.
3. Render reads `render.yaml` automatically and proposes the service.

### Step 3 — Create a Turso database

Create a Turso database and auth token first:

```bash
turso db create ble-tracker-prod
turso db show ble-tracker-prod --url
turso db tokens create ble-tracker-prod
```

Save the database URL and auth token. You will use both in Render.

### Step 4 — Set the required environment variables

Before clicking **Apply**, set:

- `ADMIN_REGISTRATION_SECRET` to a long random string such as `openssl rand -hex 32`
- `TURSO_DATABASE_URL` to your Turso libsql URL
- `TURSO_AUTH_TOKEN` to your Turso auth token

Keep `ALERT_PROXIMITY_STALE_MS=60000` unless you want a different stale-device threshold.

### Step 5 — Apply and wait

Click **Apply**. Render builds the Docker image and starts the service. The first build takes about two minutes.

Your backend will be available at:

```
https://ble-tracker-backend.onrender.com
```

> **Note:** On Render's free tier the service sleeps after 15 minutes of inactivity. The first request after sleep takes ~30 seconds to respond. Upgrade to a paid plan to avoid cold starts in production.

### Step 6 — Verify the deployment

```bash
curl https://ble-tracker-backend.onrender.com/api/health
```

Expected response:

```json
{"ok":true,"manufacturerId":"0xFF01","storage":"turso","users":0,"devices":0,"geofences":0,"alerts":0,"registrations":0}
```

### Step 7 — Optional one-time migration from the old JSON store

If you already have data in `backend/data/app.json`, import it into Turso once:

```bash
cd backend
npm install
TURSO_DATABASE_URL=<your-url> \
TURSO_AUTH_TOKEN=<your-token> \
npm run db:import-json
```

---

## Option B — Deploy with Docker on any VPS

Use this for DigitalOcean, AWS EC2, Linode, or any Linux server.

### Prerequisites

- A server running Linux with Docker installed.
- A domain name or public IP address.
- Port 8787 (or 443/80) open in the firewall.

### Steps

```bash
# 1. Clone the repository on your server
git clone https://github.com/AYUSHSHARMA9817/Ble-smart-luggage-tracker.git
cd Ble-smart-luggage-tracker/backend

# 2. Build the Docker image
docker build -t ble-tracker-backend .

# 3. Run the container with a persistent data volume
docker run -d \
  --name ble-tracker \
  --restart unless-stopped \
  -p 8787:8787 \
  -e PORT=8787 \
  -e TURSO_DATABASE_URL=<your-turso-url> \
  -e TURSO_AUTH_TOKEN=<your-turso-token> \
  -e ADMIN_REGISTRATION_SECRET=<YOUR_SECRET> \
  ble-tracker-backend
```

### Add HTTPS with Nginx (recommended)

```nginx
server {
    listen 443 ssl;
    server_name tracker.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/tracker.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/tracker.yourdomain.com/privkey.pem;

    location / {
        proxy_pass http://localhost:8787;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

Use Certbot to obtain a free Let's Encrypt TLS certificate:

```bash
sudo certbot --nginx -d tracker.yourdomain.com
```

---

## Option C — Deploy to Railway

1. Go to [https://railway.app](https://railway.app) and sign up.
2. Create a new project → **Deploy from GitHub repo**.
3. Select the repository and set the root directory to `backend`.
4. Add environment variables: `PORT=8787`, `TURSO_DATABASE_URL=<url>`, `TURSO_AUTH_TOKEN=<token>`, `ADMIN_REGISTRATION_SECRET=<secret>`.

---

## After Deployment — Connect the Android App

### Update the backend URL in the app

Open `android-app/app/src/main/java/com/bletracker/app/data/Prefs.kt` and change `DEFAULT_BACKEND_URL` to your deployed HTTPS URL:

```kotlin
const val DEFAULT_BACKEND_URL = "https://ble-tracker-backend.onrender.com"
```

This value is already set to the Render URL in the current codebase. If you chose a different host, update it to match your URL before building.

### Disable cleartext traffic

`AndroidManifest.xml` currently has `android:usesCleartextTraffic="true"` to allow local HTTP testing. Once deployed with HTTPS, remove that attribute (or set it to `false`) to enforce TLS:

```xml
<application
    android:usesCleartextTraffic="false"
    ...>
```

### Build and install the APK

```bash
cd android-app
./gradlew :app:assembleRelease
```

Sign the APK with your release keystore and install it on phones via `adb install` or distribute through Google Play.

---

## Post-Deployment Checklist

- [ ] Health endpoint returns `"ok": true`
- [ ] Backend URL in `Prefs.kt` matches the deployed HTTPS URL
- [ ] `ADMIN_REGISTRATION_SECRET` is set and stored securely
- [ ] Turso database URL and auth token are set
- [ ] TLS certificate is valid (no browser warnings)
- [ ] At least one admin device registration has been created
- [ ] At least one owner account has been created and can log in
- [ ] `android:usesCleartextTraffic` set to `false` in release build
- [ ] APK signed with a release keystore

---

## Pre-Registering Tracker Hardware

Before an owner can claim a tracker, an admin must register it:

```bash
curl -X POST https://ble-tracker-backend.onrender.com/api/admin/device-registrations \
  -H 'Content-Type: application/json' \
  -d '{
    "adminSecret": "YOUR_ADMIN_SECRET",
    "deviceId": "0x0000AB01",
    "manualCode": "ABCD1234",
    "note": "Blue travel bag unit #1"
  }'
```

Give the `manualCode` to the owner. They enter it in the app to claim the device.

---

## Environment Variable Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8787` | TCP port the HTTP server listens on |
| `ADMIN_REGISTRATION_SECRET` | *(none)* | Secret required to call `/api/admin/device-registrations` |
| `TURSO_DATABASE_URL` | *(none)* | Turso/libsql database URL |
| `TURSO_AUTH_TOKEN` | *(none)* | Turso auth token |
| `ALERT_PROXIMITY_STALE_MS` | `60000` | Milliseconds before a tracker is treated as out of contact |
