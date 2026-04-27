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
  hashPassword,
  issueRegistration,
  logSecurityEvent,
  normalizeManualCode,
  requireAuth,
  verifyManualCode,
  verifyPassword,
} from "./security.js";
import { loadDb, nextId, saveDb } from "./store.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const PUBLIC_DIR = path.resolve(__dirname, "../public");
const PORT = Number(process.env.PORT ?? 8787);

/** HTTP server – all API routes are handled by {@link handleApi}. */
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

/**
 * Background job: re-evaluate proximity alerts for all owned devices every
 * 5 seconds. This catches devices that have gone silent without sending a
 * state-change packet (e.g. battery died or moved out of range).
 */
setInterval(() => {
  const db = loadDb();
  evaluateBackgroundAlerts(db);
  saveDb(db);
}, 5_000);

server.listen(PORT, () => {
  console.log(`BLE tracker backend running on http://localhost:${PORT}`);
});

// ---------------------------------------------------------------------------
// API router
// ---------------------------------------------------------------------------

/**
 * Route all /api/* requests to the appropriate handler.
 * Applies rate limiting (120 req/min per IP+path) before routing.
 *
 * @param {http.IncomingMessage} req
 * @param {http.ServerResponse}  res
 */
async function handleApi(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const db = loadDb();
  const clientIp = req.headers["x-forwarded-for"] ?? req.socket.remoteAddress ?? "unknown";

  if (req.method === "OPTIONS") {
    respondJson(res, 204, {});
    return;
  }

  if (!checkRateLimit(`${clientIp}:${url.pathname}`, 120, 60_000)) {
    logSecurityEvent(db, "RATE_LIMIT_HIT", { clientIp, path: url.pathname });
    saveDb(db);
    respondJson(res, 429, { error: "rate limit exceeded" });
    return;
  }

  // --- GET /api/health ---
  if (req.method === "GET" && url.pathname === "/api/health") {
    respondJson(res, 200, {
      ok: true,
      manufacturerId: MANUFACTURER_ID,
      users: db.users.length,
      devices: db.devices.length,
      geofences: db.geofences.length,
      alerts: db.alerts.filter((entry) => entry.status === "open").length,
      registrations: db.deviceRegistrations.length,
    });
    return;
  }

  // --- POST /api/auth/google ---
  // Exchanges a Google ID token for a session token.
  // serverClientId is optional; when provided the token audience is verified.
  if (req.method === "POST" && url.pathname === "/api/auth/google") {
    const body = await readJson(req);
    const googleProfile = await verifyGoogleIdentity(body.idToken, body.serverClientId);
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
    saveDb(db);
    respondJson(res, 200, {
      user: serializeUser(user),
      authToken: auth.token,
      expiresAt: auth.session.expiresAt,
    });
    return;
  }

  // --- POST /api/users ---
  // Create a new account or set a password on an existing Google-linked account.
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

    const existing = db.users.find((entry) => String(entry.email ?? "").toLowerCase() === email);
    if (existing?.googleSub) {
      respondJson(res, 409, { error: "email is already linked to another sign-in method" });
      return;
    }

    if (existing?.passwordHash && existing?.passwordSalt) {
      respondJson(res, 409, { error: "account already exists" });
      return;
    }

    if (existing) {
      const passwordRecord = hashPassword(password);
      existing.name = name;
      existing.passwordSalt = passwordRecord.salt;
      existing.passwordHash = passwordRecord.hash;
      const auth = createSession(db, existing.id, req.headers["user-agent"]);
      logSecurityEvent(db, "LOCAL_PASSWORD_SET", { userId: existing.id, email: existing.email });
      saveDb(db);
      respondJson(res, 200, {
        user: serializeUser(existing),
        authToken: auth.token,
        expiresAt: auth.session.expiresAt,
      });
      return;
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
    saveDb(db);
    respondJson(res, 201, {
      user: serializeUser(user),
      authToken: auth.token,
      expiresAt: auth.session.expiresAt,
    });
    return;
  }

  // --- POST /api/auth/login ---
  if (req.method === "POST" && url.pathname === "/api/auth/login") {
    const body = await readJson(req);
    const email = String(body.email ?? "").trim().toLowerCase();
    const password = String(body.password ?? "");
    if (!email || !password) {
      respondJson(res, 400, { error: "email and password are required" });
      return;
    }

    const user = db.users.find((entry) => String(entry.email ?? "").toLowerCase() === email);
    if (!user || user.googleSub || !verifyPassword(password, user)) {
      logSecurityEvent(db, "LOCAL_LOGIN_FAILED", { email, clientIp });
      saveDb(db);
      respondJson(res, 401, { error: "invalid email or password" });
      return;
    }

    const auth = createSession(db, user.id, req.headers["user-agent"]);
    logSecurityEvent(db, "LOCAL_LOGIN_SUCCESS", { userId: user.id, email: user.email });
    saveDb(db);
    respondJson(res, 200, {
      user: serializeUser(user),
      authToken: auth.token,
      expiresAt: auth.session.expiresAt,
    });
    return;
  }

  // --- GET /api/me ---
  if (req.method === "GET" && url.pathname === "/api/me") {
    const auth = requireAuth(db, req);
    respondJson(res, 200, serializeUser(auth.user));
    return;
  }

  // --- GET /api/bootstrap ---
  // Returns the full initial payload for the authenticated user in a single request.
  if (req.method === "GET" && url.pathname === "/api/bootstrap") {
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

  // --- POST /api/admin/device-registrations ---
  // Admin-only endpoint to pre-register a device and issue a one-time manual code.
  if (req.method === "POST" && url.pathname === "/api/admin/device-registrations") {
    const body = await readJson(req);
    if (!process.env.ADMIN_REGISTRATION_SECRET || body.adminSecret !== process.env.ADMIN_REGISTRATION_SECRET) {
      logSecurityEvent(db, "ADMIN_REGISTRATION_DENIED", { clientIp });
      saveDb(db);
      respondJson(res, 403, { error: "invalid admin secret" });
      return;
    }

    const deviceId = normalizeDeviceId(body.deviceId);
    const manualCode = normalizeManualCode(body.manualCode);
    if (!deviceId || !manualCode) {
      respondJson(res, 400, { error: "deviceId and manualCode are required" });
      return;
    }

    const registration = issueRegistration(db, deviceId, manualCode, body.note ?? "");
    logSecurityEvent(db, "REGISTRATION_CODE_CREATED", { deviceId });
    saveDb(db);
    respondJson(res, 201, {
      id: registration.id,
      deviceId: registration.deviceId,
      note: registration.note,
      createdAt: registration.createdAt,
    });
    return;
  }

  // --- POST /api/devices/register ---
  // Claim a pre-registered device by supplying the one-time manual code.
  if (req.method === "POST" && url.pathname === "/api/devices/register") {
    const body = await readJson(req);
    const auth = requireAuth(db, req);
    const deviceId = normalizeDeviceId(body.deviceId);
    const manualCode = normalizeManualCode(body.manualCode);
    const registration = db.deviceRegistrations.find((entry) => entry.deviceId === deviceId);

    if (!registration) {
      logSecurityEvent(db, "DEVICE_REGISTRATION_UNKNOWN_DEVICE", { deviceId, userId: auth.user.id });
      saveDb(db);
      respondJson(res, 404, { error: "device registration not found" });
      return;
    }

    if (registration.claimedByUserId && registration.claimedByUserId !== auth.user.id) {
      logSecurityEvent(db, "DEVICE_REGISTRATION_ALREADY_CLAIMED", { deviceId, userId: auth.user.id });
      saveDb(db);
      respondJson(res, 409, { error: "device already claimed" });
      return;
    }

    if (!manualCode || !verifyManualCode(manualCode, registration)) {
      logSecurityEvent(db, "DEVICE_REGISTRATION_CODE_INVALID", { deviceId, userId: auth.user.id });
      saveDb(db);
      respondJson(res, 403, { error: "invalid manual code" });
      return;
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
      };
      db.devices.push(device);
    } else {
      device.ownerUserId = auth.user.id;
      device.displayName = body.displayName ?? device.displayName;
      device.status = "claimed";
      device.proximityMonitoringEnabled ??= true;
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
    saveDb(db);
    respondJson(res, 200, device);
    return;
  }

  // --- GET /api/devices ---
  if (req.method === "GET" && url.pathname === "/api/devices") {
    const auth = requireAuth(db, req);
    respondJson(res, 200, db.devices.filter((entry) => entry.ownerUserId === auth.user.id));
    return;
  }

  // --- DELETE /api/devices/:deviceId ---
  // Unclaims a device, removing all related alerts and notifications.
  if (req.method === "DELETE" && url.pathname.startsWith("/api/devices/")) {
    const auth = requireAuth(db, req);
    const rawDeviceId = decodeURIComponent(url.pathname.split("/").pop() ?? "");
    const deviceId = normalizeDeviceId(rawDeviceId);
    const device = db.devices.find(
      (entry) => entry.deviceId === deviceId && entry.ownerUserId === auth.user.id
    );

    if (!device) {
      respondJson(res, 404, { error: "device not found" });
      return;
    }

    device.ownerUserId = null;
    device.displayName = device.deviceId;
    device.status = "unclaimed";
    device.geofenceState = "unknown";
    device.proximityMonitoringEnabled = true;

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

    saveDb(db);
    respondJson(res, 200, { ok: true, deviceId });
    return;
  }

  // --- POST /api/devices/:deviceId/monitoring ---
  // Enable or disable proximity monitoring for a device.
  if (req.method === "POST" && url.pathname.startsWith("/api/devices/") && url.pathname.endsWith("/monitoring")) {
    const auth = requireAuth(db, req);
    const parts = url.pathname.split("/");
    const rawDeviceId = decodeURIComponent(parts[3] ?? "");
    const deviceId = normalizeDeviceId(rawDeviceId);
    const body = await readJson(req);
    const device = db.devices.find(
      (entry) => entry.deviceId === deviceId && entry.ownerUserId === auth.user.id
    );

    if (!device) {
      respondJson(res, 404, { error: "device not found" });
      return;
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

    saveDb(db);
    respondJson(res, 200, device);
    return;
  }

  // --- POST /api/sightings ---
  // Receive a BLE sighting from a scanner (Android app) and update device state.
  if (req.method === "POST" && url.pathname === "/api/sightings") {
    const body = await readJson(req);
    body.manufacturerId = normalizeManufacturerId(body.manufacturerId);
    const device = recordSighting(db, body);
    saveDb(db);
    respondJson(res, 201, device);
    return;
  }

  // --- GET /api/events ---
  if (req.method === "GET" && url.pathname === "/api/events") {
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

  // --- GET /api/alerts ---
  if (req.method === "GET" && url.pathname === "/api/alerts") {
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
    respondJson(
      res,
      200,
      alerts.slice(0, 200)
    );
    return;
  }

  // --- POST /api/alerts/ack ---
  // Acknowledge an open alert; also tracks the device's lastSeenAt so a new
  // proximity alert is not immediately re-raised for the same sighting.
  if (req.method === "POST" && url.pathname === "/api/alerts/ack") {
    const auth = requireAuth(db, req);
    const body = await readJson(req);
    const alert = db.alerts.find(
      (entry) => entry.id === body.alertId && entry.ownerUserId === auth.user.id
    );
    if (!alert) {
      respondJson(res, 404, { error: "alert not found" });
      return;
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
    saveDb(db);
    respondJson(res, 200, alert);
    return;
  }

  // --- GET /api/notifications ---
  if (req.method === "GET" && url.pathname === "/api/notifications") {
    const auth = requireAuth(db, req);
    respondJson(
      res,
      200,
      db.notifications.filter((entry) => entry.userId === auth.user.id).slice(0, 200)
    );
    return;
  }

  // --- GET /api/geofences ---
  if (req.method === "GET" && url.pathname === "/api/geofences") {
    const auth = requireAuth(db, req);
    respondJson(res, 200, db.geofences.filter((entry) => entry.userId === auth.user.id));
    return;
  }

  // --- POST /api/geofences ---
  if (req.method === "POST" && url.pathname === "/api/geofences") {
    const auth = requireAuth(db, req);
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

    const geofence = {
      id: nextId("geofence"),
      userId: auth.user.id,
      name: String(body.name).trim(),
      center: { lat, lng },
      radiusMeters,
      enabled: body.enabled ?? true,
      createdAt: new Date().toISOString(),
    };
    db.geofences.push(geofence);
    saveDb(db);
    respondJson(res, 201, geofence);
    return;
  }

  // --- DELETE /api/geofences/:geofenceId ---
  if (req.method === "DELETE" && url.pathname.startsWith("/api/geofences/")) {
    const auth = requireAuth(db, req);
    const geofenceId = url.pathname.split("/").pop();
    const index = db.geofences.findIndex(
      (entry) => entry.id === geofenceId && entry.userId === auth.user.id
    );
    if (index === -1) {
      respondJson(res, 404, { error: "geofence not found" });
      return;
    }
    const [removed] = db.geofences.splice(index, 1);
    saveDb(db);
    respondJson(res, 200, removed);
    return;
  }

  respondJson(res, 404, { error: "not found" });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Verify a Google ID token by calling the Google token-info endpoint.
 *
 * When @p serverClientId is provided the token's `aud` claim is checked
 * against it. If omitted the audience check is skipped (useful in
 * development or when the client ID is managed elsewhere).
 *
 * @param {string}      idToken        - Raw Google ID token from the client.
 * @param {string|null} serverClientId - Expected OAuth client ID, or falsy to skip.
 * @returns {Promise<{ sub: string, email: string, name: string }>}
 * @throws {Error} If the token is invalid, expired, or has the wrong audience.
 */
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

/**
 * Return a safe public representation of a user record (no password fields).
 *
 * @param {object} user - Full user record from the database.
 * @returns {{ id: string, name: string, email: string }}
 */
function serializeUser(user) {
  return {
    id: user.id,
    name: user.name,
    email: user.email,
  };
}

/**
 * Read and parse the JSON body of an incoming request.
 * Returns an empty object for requests with no body.
 *
 * @param {http.IncomingMessage} req
 * @returns {Promise<object>}
 */
async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString("utf8");
  return raw ? JSON.parse(raw) : {};
}

/**
 * Send a JSON response with CORS headers.
 *
 * @param {http.ServerResponse} res
 * @param {number}              statusCode - HTTP status code.
 * @param {object}              body       - Value to serialise as JSON.
 */
function respondJson(res, statusCode, body) {
  res.writeHead(statusCode, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET,POST,DELETE,OPTIONS",
  });
  res.end(JSON.stringify(body, null, 2));
}

/**
 * Serve a static file from the PUBLIC_DIR directory.
 * Responds 404 for missing files or paths that escape the public root.
 *
 * @param {http.IncomingMessage} req
 * @param {http.ServerResponse}  res
 */
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
