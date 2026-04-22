import crypto from "node:crypto";

import { nextId } from "./store.js";

const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 30;
const rateLimits = new Map();

export function createSession(db, userId, userAgent = "unknown") {
  const token = crypto.randomBytes(32).toString("base64url");
  const session = {
    id: nextId("sess"),
    userId,
    tokenHash: hashValue(token),
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + SESSION_TTL_MS).toISOString(),
    userAgent,
  };
  db.sessions = db.sessions.filter((entry) => entry.userId !== userId);
  db.sessions.push(session);
  return { token, session };
}

export function hashPassword(password, salt = crypto.randomBytes(16).toString("hex")) {
  const normalized = String(password ?? "");
  const hash = crypto.scryptSync(normalized, salt, 64).toString("hex");
  return { salt, hash };
}

export function verifyPassword(password, user) {
  if (!user?.passwordSalt || !user?.passwordHash) {
    return false;
  }
  const derived = crypto.scryptSync(String(password ?? ""), user.passwordSalt, 64).toString("hex");
  return crypto.timingSafeEqual(Buffer.from(derived, "hex"), Buffer.from(user.passwordHash, "hex"));
}

export function requireAuth(db, req) {
  const authHeader = req.headers.authorization ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    const error = new Error("missing bearer token");
    error.statusCode = 401;
    throw error;
  }
  const tokenHash = hashValue(authHeader.slice("Bearer ".length).trim());
  const session = db.sessions.find((entry) => entry.tokenHash === tokenHash);
  if (!session) {
    const error = new Error("invalid session");
    error.statusCode = 401;
    throw error;
  }
  if (new Date(session.expiresAt).getTime() <= Date.now()) {
    const error = new Error("session expired");
    error.statusCode = 401;
    throw error;
  }
  const user = db.users.find((entry) => entry.id === session.userId);
  if (!user) {
    const error = new Error("user not found");
    error.statusCode = 401;
    throw error;
  }
  return { session, user };
}

export function normalizeManualCode(code) {
  return String(code ?? "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
}

export function issueRegistration(db, deviceId, manualCode, note = "") {
  const { salt, hash } = hashSecret(normalizeManualCode(manualCode));
  const registration = {
    id: nextId("reg"),
    deviceId,
    salt,
    codeHash: hash,
    note,
    claimedByUserId: null,
    claimedAt: null,
    createdAt: new Date().toISOString(),
  };
  db.deviceRegistrations = db.deviceRegistrations.filter((entry) => entry.deviceId !== deviceId);
  db.deviceRegistrations.push(registration);
  return registration;
}

export function verifyManualCode(code, registration) {
  const normalized = normalizeManualCode(code);
  const derived = crypto.scryptSync(normalized, registration.salt, 64).toString("hex");
  return crypto.timingSafeEqual(Buffer.from(derived, "hex"), Buffer.from(registration.codeHash, "hex"));
}

export function logSecurityEvent(db, type, details = {}) {
  db.securityEvents.unshift({
    id: nextId("sec"),
    type,
    details,
    createdAt: new Date().toISOString(),
  });
}

export function checkRateLimit(key, limit, windowMs) {
  const now = Date.now();
  const history = rateLimits.get(key) ?? [];
  const recent = history.filter((timestamp) => now - timestamp <= windowMs);
  recent.push(now);
  rateLimits.set(key, recent);
  return recent.length <= limit;
}

function hashSecret(value, salt = crypto.randomBytes(16).toString("hex")) {
  const hash = crypto.scryptSync(String(value ?? ""), salt, 64).toString("hex");
  return { salt, hash };
}

function hashValue(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}
