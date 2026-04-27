import { createClient } from "@libsql/client";

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

const DB_ROW_ID = "primary";
const DEFAULT_DATABASE_URL = process.env.TURSO_DATABASE_URL ?? "file:./data/ble-tracker.db";
const client = createClient({
  url: DEFAULT_DATABASE_URL,
  authToken: process.env.TURSO_AUTH_TOKEN || undefined,
});

let initPromise = null;
let writeChain = Promise.resolve();

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

async function ensureInitialized() {
  if (!initPromise) {
    initPromise = initializeStore();
  }
  return initPromise;
}

async function initializeStore() {
  await client.execute(`
    CREATE TABLE IF NOT EXISTS app_state (
      id TEXT PRIMARY KEY,
      payload TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);

  const existing = await client.execute({
    sql: "SELECT id FROM app_state WHERE id = ?",
    args: [DB_ROW_ID],
  });

  if (existing.rows.length === 0) {
    await client.execute({
      sql: "INSERT INTO app_state (id, payload, updated_at) VALUES (?, ?, ?)",
      args: [DB_ROW_ID, JSON.stringify(createDefaultDb()), new Date().toISOString()],
    });
  }
}

async function fetchStateRow() {
  await ensureInitialized();
  const result = await client.execute({
    sql: "SELECT payload FROM app_state WHERE id = ?",
    args: [DB_ROW_ID],
  });
  const row = result.rows[0];
  if (!row) {
    const db = createDefaultDb();
    await overwriteDb(db);
    return db;
  }

  try {
    return normalizeDb(JSON.parse(String(row.payload ?? "{}")));
  } catch (error) {
    console.warn("Recovered from invalid DB JSON stored in Turso.", error);
    const db = createDefaultDb();
    await overwriteDb(db);
    return db;
  }
}

async function persistState(db) {
  const normalized = normalizeDb(db);
  await client.execute({
    sql: "UPDATE app_state SET payload = ?, updated_at = ? WHERE id = ?",
    args: [JSON.stringify(normalized), new Date().toISOString(), DB_ROW_ID],
  });
}

export async function loadDb() {
  return fetchStateRow();
}

export async function saveDb(db) {
  await persistState(db);
}

export async function overwriteDb(db) {
  await ensureInitialized();
  await client.execute({
    sql: `
      INSERT INTO app_state (id, payload, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        payload = excluded.payload,
        updated_at = excluded.updated_at
    `,
    args: [DB_ROW_ID, JSON.stringify(normalizeDb(db)), new Date().toISOString()],
  });
}

export async function withDb(action, { write = false } = {}) {
  if (!write) {
    const db = await loadDb();
    return action(db);
  }

  const run = writeChain.then(async () => {
    const db = await loadDb();
    const result = await action(db);
    await saveDb(db);
    return result;
  });

  writeChain = run.catch(() => {});
  return run;
}

export function nextId(prefix) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}
