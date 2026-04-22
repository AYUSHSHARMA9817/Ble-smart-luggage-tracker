import fs from "node:fs";
import path from "node:path";

const DATA_PATH = path.resolve(process.env.DATA_PATH ?? "data/app.json");
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

function createDefaultDb() {
  return structuredClone(DEFAULT_DB);
}

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

function writeDbFile(db, targetPath = DATA_PATH) {
  fs.writeFileSync(targetPath, JSON.stringify(db, null, 2));
}

function ensureFile() {
  const dir = path.dirname(DATA_PATH);
  fs.mkdirSync(dir, { recursive: true });
  if (!fs.existsSync(DATA_PATH)) {
    writeDbFile(createDefaultDb());
  }
}

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

export function saveDb(db) {
  ensureFile();
  const nextDb = normalizeDb(db);
  const tempPath = path.resolve(path.dirname(DATA_PATH), `app.${process.pid}.tmp`);
  writeDbFile(nextDb, tempPath);
  fs.renameSync(tempPath, DATA_PATH);
}

export function nextId(prefix) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}
