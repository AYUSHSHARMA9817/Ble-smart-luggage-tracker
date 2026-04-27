import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  MANUFACTURER_ID,
  evaluateBackgroundAlerts,
  normalizeDeviceId,
  normalizeManufacturerId,
  recordSighting,
} from "./logic.js";
import {
  checkRateLimit,
  createSession,
  generateManualCode,
  hashPassword,
  issueRegistration,
  logSecurityEvent,
  normalizeManualCode,
  requireAuth,
  verifyManualCode,
  verifyPassword,
} from "./security.js";
import { loadDb, nextId, withDb } from "./store.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const PUBLIC_DIR = path.resolve(__dirname, "../public");
const PORT = Number(process.env.PORT ?? 8787);

const server = http.createServer(async (req, res) => {
  try {
    if (req.url?.startsWith("/api/")) {
      await handleApi(req, res);
      return;
    }
    serveStatic(req, res);
  } catch (error) {
    respondJson(res, error.statusCode ?? 500, { error: error.message });
  }
});

setInterval(() => {
  withDb((db) => {
    evaluateBackgroundAlerts(db);
  }, { write: true }).catch((error) => {
    console.error("Background alert evaluation failed", error);
  });
}, 5_000);

server.listen(PORT, () => {
  console.log(`BLE tracker backend running on http://localhost:${PORT}`);
});

async function handleApi(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const clientIp = req.headers["x-forwarded-for"] ?? req.socket.remoteAddress ?? "unknown";

  if (req.method === "OPTIONS") {
    respondJson(res, 204, {});
    return;
  }

  if (!checkRateLimit(`${clientIp}:${url.pathname}`, 120, 60_000)) {
    await withDb((db) => {
      logSecurityEvent(db, "RATE_LIMIT_HIT", { clientIp, path: url.pathname });
    }, { write: true });
    respondJson(res, 429, { error: "rate limit exceeded" });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/health") {
    const db = await loadDb();
    respondJson(res, 200, {
      ok: true,
      manufacturerId: MANUFACTURER_ID,
      storage: "turso",
      users: db.users.length,
      devices: db.devices.length,
      geofences: db.geofences.length,
      alerts: db.alerts.filter((entry) => entry.status === "open").length,
      registrations: db.deviceRegistrations.length,
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/auth/google") {
    const body = await readJson(req);
    const googleProfile = await verifyGoogleIdentity(body.idToken, body.serverClientId);
    const result = await withDb((db) => {
      let user = db.users.find((entry) => entry.googleSub === googleProfile.sub);
      if (!user) {
        user = {
          id: nextId("user"),
          name: googleProfile.name ?? googleProfile.email,
          email: googleProfile.email,
          googleSub: googleProfile.sub,
          createdAt: new Date().toISOString(),
        };
        db.users.push(user);
      } else {
        user.name = googleProfile.name ?? user.name;
        user.email = googleProfile.email ?? user.email;
      }

      const auth = createSession(db, user.id, req.headers["user-agent"]);
      logSecurityEvent(db, "GOOGLE_LOGIN_SUCCESS", { userId: user.id, email: user.email });
      return {
        user: serializeUser(user),
        authToken: auth.token,
        expiresAt: auth.session.expiresAt,
      };
    }, { write: true });
    respondJson(res, 200, result);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/users") {
    const body = await readJson(req);
    const name = String(body.name ?? "").trim();
    const email = String(body.email ?? "").trim().toLowerCase();
    const password = String(body.password ?? "");
    if (!name || !email || !password) {
      respondJson(res, 400, { error: "name, email, and password are required" });
      return;
    }

    if (password.length < 6) {
      respondJson(res, 400, { error: "password must be at least 6 characters" });
      return;
    }

    const result = await withDb((db) => {
      const existing = db.users.find((entry) => String(entry.email ?? "").toLowerCase() === email);
      if (existing?.googleSub) {
        const error = new Error("email is already linked to another sign-in method");
        error.statusCode = 409;
        throw error;
      }

      if (existing?.passwordHash && existing?.passwordSalt) {
        const error = new Error("account already exists");
        error.statusCode = 409;
        throw error;
      }

      if (existing) {
        const passwordRecord = hashPassword(password);
        existing.name = name;
        existing.passwordSalt = passwordRecord.salt;
        existing.passwordHash = passwordRecord.hash;
        const auth = createSession(db, existing.id, req.headers["user-agent"]);
        logSecurityEvent(db, "LOCAL_PASSWORD_SET", { userId: existing.id, email: existing.email });
        return {
          statusCode: 200,
          body: {
            user: serializeUser(existing),
            authToken: auth.token,
            expiresAt: auth.session.expiresAt,
          },
        };
      }

      const passwordRecord = hashPassword(password);
      const user = {
        id: nextId("user"),
        name,
        email,
        googleSub: null,
        passwordSalt: passwordRecord.salt,
        passwordHash: passwordRecord.hash,
        createdAt: new Date().toISOString(),
      };
      db.users.push(user);
      const auth = createSession(db, user.id, req.headers["user-agent"]);
      logSecurityEvent(db, "MANUAL_USER_CREATED", { userId: user.id, email: user.email });
      return {
        statusCode: 201,
        body: {
          user: serializeUser(user),
          authToken: auth.token,
          expiresAt: auth.session.expiresAt,
        },
      };
    }, { write: true });

    respondJson(res, result.statusCode, result.body);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/auth/login") {
    const body = await readJson(req);
    const email = String(body.email ?? "").trim().toLowerCase();
    const password = String(body.password ?? "");
    if (!email || !password) {
      respondJson(res, 400, { error: "email and password are required" });
      return;
    }

    const result = await withDb((db) => {
      const user = db.users.find((entry) => String(entry.email ?? "").toLowerCase() === email);
      if (!user || user.googleSub || !verifyPassword(password, user)) {
        logSecurityEvent(db, "LOCAL_LOGIN_FAILED", { email, clientIp });
        const error = new Error("invalid email or password");
        error.statusCode = 401;
        throw error;
      }

      const auth = createSession(db, user.id, req.headers["user-agent"]);
      logSecurityEvent(db, "LOCAL_LOGIN_SUCCESS", { userId: user.id, email: user.email });
      return {
        user: serializeUser(user),
        authToken: auth.token,
        expiresAt: auth.session.expiresAt,
      };
    }, { write: true });

    respondJson(res, 200, result);
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/me") {
    const db = await loadDb();
    const auth = requireAuth(db, req);
    respondJson(res, 200, serializeUser(auth.user));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/bootstrap") {
    const db = await loadDb();
    const auth = requireAuth(db, req);
    respondJson(res, 200, {
      user: serializeUser(auth.user),
      devices: db.devices.filter((entry) => entry.ownerUserId === auth.user.id),
      alerts: db.alerts.filter((entry) => entry.ownerUserId === auth.user.id).slice(0, 200),
      notifications: db.notifications.filter((entry) => entry.userId === auth.user.id).slice(0, 200),
      geofences: db.geofences.filter((entry) => entry.userId === auth.user.id),
      serverTime: new Date().toISOString(),
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/admin/device-registrations") {
    const body = await readJson(req);
    const result = await withDb((db) => {
      if (!process.env.ADMIN_REGISTRATION_SECRET || body.adminSecret !== process.env.ADMIN_REGISTRATION_SECRET) {
        logSecurityEvent(db, "ADMIN_REGISTRATION_DENIED", { clientIp });
        const error = new Error("invalid admin secret");
        error.statusCode = 403;
        throw error;
      }

      const deviceId = normalizeDeviceId(body.deviceId);
      const manualCode = normalizeManualCode(body.manualCode) || generateManualCode();
      if (!deviceId) {
        const error = new Error("deviceId is required");
        error.statusCode = 400;
        throw error;
      }

      const registration = issueRegistration(db, deviceId, manualCode, body.note ?? "");
      logSecurityEvent(db, "REGISTRATION_CODE_CREATED", { deviceId });
      return {
        id: registration.id,
        deviceId: registration.deviceId,
        manualCode,
        note: registration.note,
        createdAt: registration.createdAt,
      };
    }, { write: true });

    respondJson(res, 201, result);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/devices/register") {
    const body = await readJson(req);
    const result = await withDb((db) => {
      const auth = requireAuth(db, req);
      const deviceId = normalizeDeviceId(body.deviceId);
      const manualCode = normalizeManualCode(body.manualCode);
      const registration = db.deviceRegistrations.find((entry) => entry.deviceId === deviceId);

      if (!registration) {
        logSecurityEvent(db, "DEVICE_REGISTRATION_UNKNOWN_DEVICE", { deviceId, userId: auth.user.id });
        const error = new Error("device registration not found");
        error.statusCode = 404;
        throw error;
      }

      if (registration.claimedByUserId && registration.claimedByUserId !== auth.user.id) {
        logSecurityEvent(db, "DEVICE_REGISTRATION_ALREADY_CLAIMED", { deviceId, userId: auth.user.id });
        const error = new Error("device already claimed");
        error.statusCode = 409;
        throw error;
      }

      if (!manualCode || !verifyManualCode(manualCode, registration)) {
        logSecurityEvent(db, "DEVICE_REGISTRATION_CODE_INVALID", { deviceId, userId: auth.user.id });
        const error = new Error("invalid manual code");
        error.statusCode = 403;
        throw error;
      }

      let device = db.devices.find((entry) => entry.deviceId === deviceId);
      if (!device) {
        device = {
          id: nextId("device"),
          deviceId,
          ownerUserId: auth.user.id,
          displayName: body.displayName ?? deviceId,
          createdAt: new Date().toISOString(),
          lastSeenAt: null,
          lastLocation: null,
          lastRssi: null,
          lastPacket: null,
          geofenceState: "unknown",
          status: "claimed",
          proximityMonitoringEnabled: true,
          proximityAlertSentForSeenAt: null,
        };
        db.devices.push(device);
      } else {
        device.ownerUserId = auth.user.id;
        device.displayName = body.displayName ?? device.displayName;
        device.status = "claimed";
        device.proximityMonitoringEnabled ??= true;
        device.proximityAlertSentForSeenAt ??= null;
      }

      registration.claimedByUserId = auth.user.id;
      registration.claimedAt = new Date().toISOString();

      db.events.unshift({
        id: nextId("event"),
        deviceId,
        ownerUserId: auth.user.id,
        type: "DEVICE_REGISTERED",
        createdAt: new Date().toISOString(),
      });
      logSecurityEvent(db, "DEVICE_REGISTERED", { deviceId, userId: auth.user.id });
      return device;
    }, { write: true });

    respondJson(res, 200, result);
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/devices") {
    const db = await loadDb();
    const auth = requireAuth(db, req);
    respondJson(res, 200, db.devices.filter((entry) => entry.ownerUserId === auth.user.id));
    return;
  }

  if (req.method === "DELETE" && url.pathname.startsWith("/api/devices/")) {
    const rawDeviceId = decodeURIComponent(url.pathname.split("/").pop() ?? "");
    const deviceId = normalizeDeviceId(rawDeviceId);
    const result = await withDb((db) => {
      const auth = requireAuth(db, req);
      const device = db.devices.find(
        (entry) => entry.deviceId === deviceId && entry.ownerUserId === auth.user.id
      );

      if (!device) {
        const error = new Error("device not found");
        error.statusCode = 404;
        throw error;
      }

      device.ownerUserId = null;
      device.displayName = device.deviceId;
      device.status = "unclaimed";
      device.geofenceState = "unknown";
      device.proximityMonitoringEnabled = true;
      device.proximityAlertSentForSeenAt = null;

      const registration = db.deviceRegistrations.find((entry) => entry.deviceId === deviceId);
      if (registration?.claimedByUserId === auth.user.id) {
        registration.claimedByUserId = null;
        registration.claimedAt = null;
      }

      db.alerts = db.alerts.filter((entry) => !(entry.deviceId === deviceId && entry.ownerUserId === auth.user.id));
      db.notifications = db.notifications.filter(
        (entry) => !(entry.userId === auth.user.id && db.alerts.every((alert) => alert.id !== entry.alertId))
      );
      db.events.unshift({
        id: nextId("event"),
        deviceId,
        ownerUserId: auth.user.id,
        type: "DEVICE_REMOVED",
        createdAt: new Date().toISOString(),
      });

      return { ok: true, deviceId };
    }, { write: true });

    respondJson(res, 200, result);
    return;
  }

  if (req.method === "POST" && url.pathname.startsWith("/api/devices/") && url.pathname.endsWith("/monitoring")) {
    const parts = url.pathname.split("/");
    const rawDeviceId = decodeURIComponent(parts[3] ?? "");
    const deviceId = normalizeDeviceId(rawDeviceId);
    const body = await readJson(req);
    const result = await withDb((db) => {
      const auth = requireAuth(db, req);
      const device = db.devices.find(
        (entry) => entry.deviceId === deviceId && entry.ownerUserId === auth.user.id
      );

      if (!device) {
        const error = new Error("device not found");
        error.statusCode = 404;
        throw error;
      }

      device.proximityMonitoringEnabled = body.enabled !== false;
      if (!device.proximityMonitoringEnabled) {
        const openAlert = db.alerts.find(
          (entry) => entry.type === "PROXIMITY_LOST" && entry.deviceId === deviceId && entry.status === "open"
        );
        if (openAlert) {
          openAlert.status = "closed";
          openAlert.closedAt = new Date().toISOString();
        }
      }

      return device;
    }, { write: true });

    respondJson(res, 200, result);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/sightings") {
    const body = await readJson(req);
    body.manufacturerId = normalizeManufacturerId(body.manufacturerId);
    const result = await withDb((db) => recordSighting(db, body), { write: true });
    respondJson(res, 201, result);
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/events") {
    const db = await loadDb();
    const auth = requireAuth(db, req);
    const deviceId = url.searchParams.get("deviceId");
    const events = deviceId
      ? db.events.filter(
          (entry) =>
            entry.deviceId === normalizeDeviceId(deviceId) &&
            entry.ownerUserId === auth.user.id
        )
      : db.events.filter((entry) => entry.ownerUserId === auth.user.id);
    respondJson(res, 200, events.slice(0, 200));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/alerts") {
    const db = await loadDb();
    const auth = requireAuth(db, req);
    const since = url.searchParams.get("since");
    const openOnly = url.searchParams.get("openOnly") === "true";
    let alerts = db.alerts.filter((entry) => entry.ownerUserId === auth.user.id);
    if (since) {
      const sinceMs = new Date(since).getTime();
      if (!Number.isNaN(sinceMs)) {
        alerts = alerts.filter((entry) => new Date(entry.createdAt).getTime() > sinceMs);
      }
    }
    if (openOnly) {
      alerts = alerts.filter((entry) => entry.status === "open");
    }
    respondJson(res, 200, alerts.slice(0, 200));
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/alerts/ack") {
    const body = await readJson(req);
    const result = await withDb((db) => {
      const auth = requireAuth(db, req);
      const alert = db.alerts.find(
        (entry) => entry.id === body.alertId && entry.ownerUserId === auth.user.id
      );
      if (!alert) {
        const error = new Error("alert not found");
        error.statusCode = 404;
        throw error;
      }
      alert.status = "acknowledged";
      alert.acknowledgedAt = new Date().toISOString();
      if (alert.type === "PROXIMITY_LOST") {
        const device = db.devices.find(
          (entry) => entry.deviceId === alert.deviceId && entry.ownerUserId === auth.user.id
        );
        if (device?.lastSeenAt) {
          device.proximityAlertSentForSeenAt = device.lastSeenAt;
        }
      }
      return alert;
    }, { write: true });

    respondJson(res, 200, result);
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/notifications") {
    const db = await loadDb();
    const auth = requireAuth(db, req);
    respondJson(
      res,
      200,
      db.notifications.filter((entry) => entry.userId === auth.user.id).slice(0, 200)
    );
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/geofences") {
    const db = await loadDb();
    const auth = requireAuth(db, req);
    respondJson(res, 200, db.geofences.filter((entry) => entry.userId === auth.user.id));
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/geofences") {
    const body = await readJson(req);
    const lat = Number(body.center?.lat);
    const lng = Number(body.center?.lng);
    const radiusMeters = Number(body.radiusMeters);

    if (!body.name || String(body.name).trim() === "") {
      respondJson(res, 400, { error: "name is required" });
      return;
    }
    if (Number.isNaN(lat) || lat < -90 || lat > 90) {
      respondJson(res, 400, { error: "center.lat must be a number between -90 and 90" });
      return;
    }
    if (Number.isNaN(lng) || lng < -180 || lng > 180) {
      respondJson(res, 400, { error: "center.lng must be a number between -180 and 180" });
      return;
    }
    if (Number.isNaN(radiusMeters) || radiusMeters <= 0) {
      respondJson(res, 400, { error: "radiusMeters must be a positive number" });
      return;
    }

    const geofence = await withDb((db) => {
      const auth = requireAuth(db, req);
      const nextGeofence = {
        id: nextId("geofence"),
        userId: auth.user.id,
        name: String(body.name).trim(),
        center: { lat, lng },
        radiusMeters,
        enabled: body.enabled ?? true,
        createdAt: new Date().toISOString(),
      };
      db.geofences.push(nextGeofence);
      return nextGeofence;
    }, { write: true });

    respondJson(res, 201, geofence);
    return;
  }

  if (req.method === "DELETE" && url.pathname.startsWith("/api/geofences/")) {
    const geofenceId = url.pathname.split("/").pop();
    const removed = await withDb((db) => {
      const auth = requireAuth(db, req);
      const index = db.geofences.findIndex(
        (entry) => entry.id === geofenceId && entry.userId === auth.user.id
      );
      if (index === -1) {
        const error = new Error("geofence not found");
        error.statusCode = 404;
        throw error;
      }
      const [deleted] = db.geofences.splice(index, 1);
      return deleted;
    }, { write: true });

    respondJson(res, 200, removed);
    return;
  }

  respondJson(res, 404, { error: "not found" });
}

async function verifyGoogleIdentity(idToken, serverClientId) {
  if (!idToken) {
    throw new Error("idToken is required");
  }

  const response = await fetch(
    `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`
  );
  if (!response.ok) {
    throw new Error("google token verification failed");
  }

  const payload = await response.json();
  if (serverClientId && payload.aud !== serverClientId) {
    throw new Error("google token audience mismatch");
  }
  if (!payload.sub || !payload.email) {
    throw new Error("google token missing required claims");
  }

  return {
    sub: payload.sub,
    email: payload.email,
    name: payload.name ?? payload.given_name ?? payload.email,
  };
}

function serializeUser(user) {
  return {
    id: user.id,
    name: user.name,
    email: user.email,
  };
}

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString("utf8");
  return raw ? JSON.parse(raw) : {};
}

function respondJson(res, statusCode, body) {
  res.writeHead(statusCode, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET,POST,DELETE,OPTIONS",
  });
  res.end(JSON.stringify(body, null, 2));
}

function serveStatic(req, res) {
  if (req.method === "OPTIONS") {
    respondJson(res, 204, {});
    return;
  }

  const pathname = req.url === "/" ? "/index.html" : req.url;
  const filePath = path.join(PUBLIC_DIR, pathname);
  if (!filePath.startsWith(PUBLIC_DIR) || !fs.existsSync(filePath)) {
    res.writeHead(404);
    res.end("Not found");
    return;
  }

  const ext = path.extname(filePath);
  const contentType =
    ext === ".html"
      ? "text/html; charset=utf-8"
      : ext === ".css"
        ? "text/css; charset=utf-8"
        : "application/javascript; charset=utf-8";
  res.writeHead(200, { "Content-Type": contentType });
  res.end(fs.readFileSync(filePath));
}
