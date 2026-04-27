import crypto from "node:crypto";

import { nextId } from "./store.js";

/** Session lifetime: 30 days in milliseconds. */
const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 30;

/**
 * Per-key request-timestamp histories used by {@link checkRateLimit}.
 * Keys are strings of the form `"<ip>:<path>"`.
 * Entries are pruned after their window expires so the Map does not grow
 * indefinitely.
 * @type {Map<string, number[]>}
 */
const rateLimits = new Map();

/**
 * Create a new authenticated session for the given user, replacing any
 * existing session for that user.
 *
 * @param {object} db        - In-memory database object.
 * @param {string} userId    - ID of the user to create the session for.
 * @param {string} userAgent - User-agent string from the request headers.
 * @returns {{ token: string, session: object }} Plain-text token and the
 *   persisted session record (token is hashed inside the record).
 */
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
  // Replace any previous session for this user (single active session per user).
  db.sessions = db.sessions.filter((entry) => entry.userId !== userId);
  db.sessions.push(session);
  return { token, session };
}

/**
 * Hash a password with a random salt using scrypt.
 *
 * @param {string} password - Plain-text password.
 * @param {string} [salt]   - Hex-encoded salt. Auto-generated when omitted.
 * @returns {{ salt: string, hash: string }}
 */
export function hashPassword(password, salt = crypto.randomBytes(16).toString("hex")) {
  const normalized = String(password ?? "");
  const hash = crypto.scryptSync(normalized, salt, 64).toString("hex");
  return { salt, hash };
}

/**
 * Verify a plain-text password against a stored hash using a timing-safe
 * comparison to mitigate timing-based enumeration attacks.
 *
 * @param {string} password - Plain-text password to verify.
 * @param {object} user     - User record containing passwordSalt and passwordHash.
 * @returns {boolean} True if the password matches.
 */
export function verifyPassword(password, user) {
  if (!user?.passwordSalt || !user?.passwordHash) {
    return false;
  }
  const derived = crypto.scryptSync(String(password ?? ""), user.passwordSalt, 64).toString("hex");
  return crypto.timingSafeEqual(Buffer.from(derived, "hex"), Buffer.from(user.passwordHash, "hex"));
}

/**
 * Authenticate the incoming HTTP request via a Bearer token.
 *
 * Throws an Error with a `statusCode` property on failure so the caller can
 * respond with the appropriate HTTP status code.
 *
 * @param {object} db  - In-memory database object.
 * @param {object} req - Node.js IncomingMessage.
 * @returns {{ session: object, user: object }} Validated session and user.
 * @throws {Error} 401 if the token is missing, invalid, or expired.
 */
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

/**
 * Normalise a manual device-registration code to uppercase alphanumeric
 * characters only, so users can enter codes with spaces, dashes, etc.
 *
 * @param {*} code - Raw input from the user.
 * @returns {string} Normalised code string.
 */
export function normalizeManualCode(code) {
  return String(code ?? "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
}

/**
 * Create (or replace) a device-registration record containing a hashed
 * manual code that a user must supply to claim the device.
 *
 * @param {object} db         - In-memory database object.
 * @param {string} deviceId   - Normalised device ID string.
 * @param {string} manualCode - Plain-text one-time code to hash and store.
 * @param {string} [note]     - Optional human-readable note for the record.
 * @returns {object} The new registration record.
 */
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
  // Replace any existing registration for this device so old codes are invalidated.
  db.deviceRegistrations = db.deviceRegistrations.filter((entry) => entry.deviceId !== deviceId);
  db.deviceRegistrations.push(registration);
  return registration;
}

/**
 * Verify a user-supplied manual code against a stored hashed registration
 * using a timing-safe comparison.
 *
 * @param {string} code         - Plain-text code from the user.
 * @param {object} registration - Registration record containing salt and codeHash.
 * @returns {boolean} True if the code matches.
 */
export function verifyManualCode(code, registration) {
  const normalized = normalizeManualCode(code);
  const derived = crypto.scryptSync(normalized, registration.salt, 64).toString("hex");
  return crypto.timingSafeEqual(Buffer.from(derived, "hex"), Buffer.from(registration.codeHash, "hex"));
}

/**
 * Prepend a security-event record to the database audit log.
 *
 * @param {object} db      - In-memory database object.
 * @param {string} type    - Event type identifier (e.g. "LOCAL_LOGIN_FAILED").
 * @param {object} details - Arbitrary additional details to store with the event.
 */
export function logSecurityEvent(db, type, details = {}) {
  db.securityEvents.unshift({
    id: nextId("sec"),
    type,
    details,
    createdAt: new Date().toISOString(),
  });
}

/**
 * Sliding-window rate limiter.
 *
 * Tracks request timestamps per key and returns false when the number of
 * requests within the last @p windowMs milliseconds exceeds @p limit.
 *
 * Map entries whose timestamp arrays become empty after pruning are deleted
 * to prevent unbounded memory growth in long-running processes.
 *
 * @param {string} key      - Unique identifier for the rate-limit bucket (e.g. `"<ip>:<path>"`).
 * @param {number} limit    - Maximum number of requests allowed within the window.
 * @param {number} windowMs - Sliding window duration in milliseconds.
 * @returns {boolean} True if the request is within the limit.
 */
export function checkRateLimit(key, limit, windowMs) {
  const now = Date.now();
  const history = rateLimits.get(key) ?? [];
  const recent = history.filter((timestamp) => now - timestamp <= windowMs);
  recent.push(now);
  if (recent.length > 1) {
    rateLimits.set(key, recent);
  } else if (recent.length === 1) {
    // First request in this window; store the entry.
    rateLimits.set(key, recent);
  } else {
    // No recent requests remain after pruning; remove the key to free memory.
    rateLimits.delete(key);
  }
  return recent.length <= limit;
}

/**
 * Hash an arbitrary secret value with a random salt using scrypt.
 * Used for manual device-registration codes.
 *
 * @param {string} value  - Plain-text value to hash.
 * @param {string} [salt] - Hex-encoded salt. Auto-generated when omitted.
 * @returns {{ salt: string, hash: string }}
 */
function hashSecret(value, salt = crypto.randomBytes(16).toString("hex")) {
  const hash = crypto.scryptSync(String(value ?? ""), salt, 64).toString("hex");
  return { salt, hash };
}

/**
 * Compute a SHA-256 hex digest of the given value.
 * Used to store session tokens without keeping the plain-text token on disk.
 *
 * @param {string} value - Value to hash.
 * @returns {string} Hex-encoded SHA-256 digest.
 */
function hashValue(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}
