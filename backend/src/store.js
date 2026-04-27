import fs from "node:fs";
import path from "node:path";

/**
 * Path to the JSON database file. Overridable via the DATA_PATH environment
 * variable, which is useful in production deployments with a persistent volume.
 */
const DATA_PATH = path.resolve(process.env.DATA_PATH ?? "data/app.json");

/** Shape of a freshly initialised (empty) database. */
const DEFAULT_DB = {
  users: [],
  devices: [],
  geofences: [],
  events: [],
  alerts: [],
  notifications: [],
  scannerSightings: [],
  sessions: [],
  deviceRegistrations: [],
  securityEvents: [],
};

/**
 * Return a deep clone of the default empty database.
 * @returns {object}
 */
function createDefaultDb() {
  return structuredClone(DEFAULT_DB);
}

/**
 * Ensure a loaded database object has all required top-level array keys.
 * Adds missing keys (e.g. when loading a database from an older schema
 * that predates a newly added collection).
 *
 * @param {object|null|undefined} db - Raw parsed JSON object.
 * @returns {object} The same object with all keys guaranteed to be present.
 */
function normalizeDb(db) {
  const normalized = db && typeof db === "object" ? db : {};
  normalized.users ??= [];
  normalized.devices ??= [];
  normalized.geofences ??= [];
  normalized.events ??= [];
  normalized.alerts ??= [];
  normalized.notifications ??= [];
  normalized.scannerSightings ??= [];
  normalized.sessions ??= [];
  normalized.deviceRegistrations ??= [];
  normalized.securityEvents ??= [];
  return normalized;
}

/**
 * Synchronously write a database object to @p targetPath as formatted JSON.
 *
 * @param {object} db
 * @param {string} [targetPath] - Defaults to {@link DATA_PATH}.
 */
function writeDbFile(db, targetPath = DATA_PATH) {
  fs.writeFileSync(targetPath, JSON.stringify(db, null, 2));
}

/**
 * Create the data directory and database file if they do not already exist,
 * seeding the file with an empty default database.
 */
function ensureFile() {
  const dir = path.dirname(DATA_PATH);
  fs.mkdirSync(dir, { recursive: true });
  if (!fs.existsSync(DATA_PATH)) {
    writeDbFile(createDefaultDb());
  }
}

/**
 * Load the database from disk. Creates the file with defaults if it is
 * missing or empty. If the file contains invalid JSON, the corrupt file is
 * renamed to a timestamped backup and a fresh default database is returned.
 *
 * @returns {object} The in-memory database object.
 */
export function loadDb() {
  ensureFile();
  const raw = fs.readFileSync(DATA_PATH, "utf8").trim();
  if (!raw) {
    const db = createDefaultDb();
    writeDbFile(db);
    return db;
  }

  try {
    return normalizeDb(JSON.parse(raw));
  } catch (error) {
    const backupPath = path.resolve(
      path.dirname(DATA_PATH),
      `app.corrupt.${Date.now()}.json`
    );
    fs.renameSync(DATA_PATH, backupPath);
    const db = createDefaultDb();
    writeDbFile(db);
    console.warn(
      `Recovered from invalid DB JSON. Backed up corrupt file to ${backupPath}.`,
      error
    );
    return db;
  }
}

/**
 * Persist the database to disk atomically using a write-to-temp-then-rename
 * strategy to prevent a partially written file from corrupting the database
 * on crash or power loss.
 *
 * @param {object} db - In-memory database object to persist.
 */
export function saveDb(db) {
  ensureFile();
  const nextDb = normalizeDb(db);
  const tempPath = path.resolve(path.dirname(DATA_PATH), `app.${process.pid}.tmp`);
  writeDbFile(nextDb, tempPath);
  fs.renameSync(tempPath, DATA_PATH);
}

/**
 * Generate a unique ID string with the given prefix.
 * Combines the current epoch timestamp and a short random suffix to make
 * collisions extremely unlikely even under rapid sequential creation.
 *
 * @param {string} prefix - Short label identifying the record type (e.g. "user").
 * @returns {string} e.g. `"user_1714000000000_a3b7c2"`
 */
export function nextId(prefix) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}
