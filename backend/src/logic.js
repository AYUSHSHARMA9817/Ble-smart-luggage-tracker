import { nextId } from "./store.js";

/** Manufacturer ID expected in every BLE advertisement payload. */
export const MANUFACTURER_ID = "0xFF01";

/** Numeric packet-type constants matching the firmware definitions. */
export const PACKET_TYPES = {
  HEARTBEAT: 0,
  STATE_CHANGE: 1,
  SELF_TEST: 2,
};

/** Sightings older than this threshold trigger a PROXIMITY_LOST alert. */
const STALE_SIGHTING_MS = 60_000;

/**
 * Normalise a device ID to the canonical `"0xXXXXXXXX"` string format
 * (8 uppercase hex digits). Accepts numbers, hex strings, decimal strings,
 * or already-normalised strings.
 *
 * @param {number|string|null|undefined} deviceId
 * @returns {string} Normalised device ID, or empty string for invalid input.
 */
export function normalizeDeviceId(deviceId) {
  if (typeof deviceId === "number") {
    return `0x${deviceId.toString(16).toUpperCase().padStart(8, "0")}`;
  }

  const trimmed = String(deviceId ?? "").trim();
  if (!trimmed) {
    return "";
  }

  if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
    return `0x${trimmed.slice(2).toUpperCase().padStart(8, "0")}`;
  }

  const parsed = Number.parseInt(trimmed, 10);
  if (!Number.isNaN(parsed)) {
    return `0x${parsed.toString(16).toUpperCase().padStart(8, "0")}`;
  }

  return trimmed;
}

/**
 * Normalise a manufacturer ID to the canonical `"0xXXXX"` string format.
 * Defaults to {@link MANUFACTURER_ID} for empty input.
 *
 * @param {string|null|undefined} manufacturerId
 * @returns {string} Normalised manufacturer ID string.
 */
export function normalizeManufacturerId(manufacturerId) {
  const value = String(manufacturerId ?? "").trim();
  if (!value) {
    return MANUFACTURER_ID;
  }

  if (value.startsWith("0x") || value.startsWith("0X")) {
    return `0x${value.slice(2).toUpperCase()}`;
  }

  return `0x${value.toUpperCase()}`;
}

/**
 * Decode a raw health-status byte (from the firmware packet) into named
 * boolean fault flags.
 *
 * @param {number|string|null|undefined} healthStatus - Raw health-status byte.
 * @returns {{ reedFault: boolean, bootContradiction: boolean, adcFault: boolean }}
 */
export function parseHealthBits(healthStatus) {
  const value = Number(healthStatus ?? 0);
  return {
    reedFault: Boolean(value & (1 << 0)),
    bootContradiction: Boolean(value & (1 << 1)),
    adcFault: Boolean(value & (1 << 2)),
  };
}

/**
 * Convert a numeric packet type to its human-readable name.
 *
 * @param {number|string} packetType
 * @returns {string} One of "HEARTBEAT", "STATE_CHANGE", "SELF_TEST", or "UNKNOWN".
 */
export function packetTypeName(packetType) {
  switch (Number(packetType)) {
    case PACKET_TYPES.HEARTBEAT:
      return "HEARTBEAT";
    case PACKET_TYPES.STATE_CHANGE:
      return "STATE_CHANGE";
    case PACKET_TYPES.SELF_TEST:
      return "SELF_TEST";
    default:
      return "UNKNOWN";
  }
}

/**
 * Convert a numeric bag state to its human-readable name.
 *
 * @param {number|string} bagState - 0 = closed, 1 = open.
 * @returns {"OPEN"|"CLOSED"}
 */
export function bagStateName(bagState) {
  return Number(bagState) === 1 ? "OPEN" : "CLOSED";
}

/**
 * Convert a numeric battery level to its human-readable name.
 *
 * @param {number|string} batteryLevel - 0–3 corresponding to CRITICAL/LOW/MEDIUM/GOOD.
 * @returns {string} Battery level name or "UNKNOWN".
 */
export function batteryLevelName(batteryLevel) {
  switch (Number(batteryLevel)) {
    case 0:
      return "CRITICAL";
    case 1:
      return "LOW";
    case 2:
      return "MEDIUM";
    case 3:
      return "GOOD";
    default:
      return "UNKNOWN";
  }
}

/**
 * Determine whether @p currentSeq represents a newer packet than @p previousSeq.
 *
 * Sequence numbers are 8-bit unsigned integers that wrap from 255 → 0.
 * A forward distance of 1–127 (half the 256-value space) is treated as "new"
 * to handle both normal increment and wrap-around while rejecting replayed
 * packets (distance 0) and improbably large jumps (distance 128–255).
 *
 * @param {number|null|undefined} previousSeq
 * @param {number}                currentSeq
 * @returns {boolean} True if currentSeq is newer than previousSeq.
 */
function isNewSequence(previousSeq, currentSeq) {
  if (previousSeq === null || previousSeq === undefined) {
    return true;
  }

  const prev = Number(previousSeq) & 0xff;
  const curr = Number(currentSeq) & 0xff;
  const diff = (curr - prev + 256) % 256;

  if (diff === 0) {
    return false;
  }

  // Treat a forward distance up to half the sequence space as new.
  return diff <= 127;
}

/**
 * Test whether a GPS coordinate falls within a circular geofence.
 * Uses the Haversine formula for great-circle distance.
 *
 * @param {{ lat: number, lng: number }|null} location  - Point to test.
 * @param {{ center: { lat: number, lng: number }, radiusMeters: number }} geofence
 * @returns {boolean} True if the point is inside the geofence radius.
 */
function pointInGeofence(location, geofence) {
  if (!location || typeof location.lat !== "number" || typeof location.lng !== "number") {
    return false;
  }

  const earthRadiusMeters = 6371000;
  const dLat = toRadians(geofence.center.lat - location.lat);
  const dLng = toRadians(geofence.center.lng - location.lng);
  const lat1 = toRadians(location.lat);
  const lat2 = toRadians(geofence.center.lat);

  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.sin(dLng / 2) ** 2 * Math.cos(lat1) * Math.cos(lat2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  const distance = earthRadiusMeters * c;
  return distance <= geofence.radiusMeters;
}

/**
 * Convert degrees to radians.
 * @param {number} value - Angle in degrees.
 * @returns {number} Angle in radians.
 */
function toRadians(value) {
  return (value * Math.PI) / 180;
}

/**
 * Create a new alert of the given type for a device if no open alert of that
 * type already exists. Always creates an accompanying notification record.
 *
 * @param {object} db      - In-memory database object.
 * @param {string} type    - Alert type string (e.g. "BAG_OPENED").
 * @param {object} device  - Device record the alert belongs to.
 * @param {string} message - Human-readable alert message.
 * @param {object} [extra] - Additional fields merged into the alert record.
 * @returns {object} The existing open alert or the newly created one.
 */
function createAlert(db, type, device, message, extra = {}) {
  const alreadyOpen = db.alerts.find(
    (alert) => alert.type === type && alert.deviceId === device.deviceId && alert.status === "open"
  );
  if (alreadyOpen) {
    return alreadyOpen;
  }

  const alert = {
    id: nextId("alert"),
    type,
    status: "open",
    deviceId: device.deviceId,
    ownerUserId: device.ownerUserId,
    message,
    createdAt: new Date().toISOString(),
    ...extra,
  };
  db.alerts.unshift(alert);

  db.notifications.unshift({
    id: nextId("notification"),
    userId: device.ownerUserId,
    alertId: alert.id,
    title: type.replaceAll("_", " "),
    message,
    createdAt: new Date().toISOString(),
    channel: "mock",
  });

  return alert;
}

/**
 * Close any open alert of the given type for a device.
 *
 * @param {object} db       - In-memory database object.
 * @param {string} type     - Alert type to close.
 * @param {string} deviceId - Normalised device ID string.
 */
function closeAlert(db, type, deviceId) {
  const alert = db.alerts.find(
    (entry) => entry.type === type && entry.deviceId === deviceId && entry.status === "open"
  );
  if (alert) {
    alert.status = "closed";
    alert.closedAt = new Date().toISOString();
  }
}

/**
 * Record a BLE sighting received from a scanner and update the device state.
 *
 * Duplicate packets (same sequence number) are ignored and logged as
 * SIGHTING_IGNORED_DUPLICATE events. For new packets, device state is updated
 * and {@link evaluateRealtimeAlerts} is called to fire any necessary alerts.
 *
 * @param {object} db      - In-memory database object (mutated in place).
 * @param {object} payload - Sighting payload from the scanner.
 * @returns {object} The updated (or newly created) device record.
 * @throws {Error} If manufacturerId or deviceId are invalid.
 */
export function recordSighting(db, payload) {
  const manufacturerId = normalizeManufacturerId(payload.manufacturerId);
  if (manufacturerId !== MANUFACTURER_ID) {
    throw new Error(`manufacturerId must be ${MANUFACTURER_ID}`);
  }

  const normalizedDeviceId = normalizeDeviceId(payload.deviceId);
  if (!normalizedDeviceId) {
    throw new Error("deviceId is required");
  }

  const seenAt = payload.seenAt ?? new Date().toISOString();
  const packet = {
    bagState: Number(payload.packet?.bagState ?? payload.bagState ?? 0),
    batteryLevel: Number(payload.packet?.batteryLevel ?? payload.batteryLevel ?? 0),
    seqNum: Number(payload.packet?.seqNum ?? payload.seqNum ?? 0),
    packetType: Number(payload.packet?.packetType ?? payload.packetType ?? 0),
    healthStatus: Number(payload.packet?.healthStatus ?? payload.healthStatus ?? 0),
    daysSinceChange: Number(payload.packet?.daysSinceChange ?? payload.daysSinceChange ?? 0),
  };

  let device = db.devices.find((entry) => entry.deviceId === normalizedDeviceId);
  if (!device) {
    device = {
      id: nextId("device"),
      deviceId: normalizedDeviceId,
      ownerUserId: null,
      displayName: normalizedDeviceId,
      createdAt: new Date().toISOString(),
      lastSeenAt: null,
      lastLocation: null,
      lastRssi: null,
      lastPacket: null,
      lastSequence: null,
      lastState: null,
      geofenceState: "unknown",
      status: "unclaimed",
      proximityMonitoringEnabled: true,
      proximityAlertSentForSeenAt: null,
    };
    db.devices.push(device);
  }

  if (!isNewSequence(device.lastSequence, packet.seqNum)) {
    db.events.unshift({
      id: nextId("event"),
      deviceId: normalizedDeviceId,
      ownerUserId: device.ownerUserId,
      type: "SIGHTING_IGNORED_DUPLICATE",
      seenAt,
      seqNum: packet.seqNum,
    });
    return device;
  }

  const previousState = device.lastState;
  device.proximityMonitoringEnabled ??= true;
  // Reset tracking flag so a new PROXIMITY_LOST alert can be raised if the
  // device goes silent again after this fresh sighting.
  device.proximityAlertSentForSeenAt = null;

  device.lastSeenAt = seenAt;
  device.lastLocation = payload.location ?? null;
  device.lastRssi = payload.rssi ?? null;
  device.lastSequence = packet.seqNum;
  device.lastState = packet.bagState;
  device.lastPacket = {
    ...packet,
    packetTypeName: packetTypeName(packet.packetType),
    bagStateName: bagStateName(packet.bagState),
    batteryLevelName: batteryLevelName(packet.batteryLevel),
    health: parseHealthBits(packet.healthStatus),
  };

  db.scannerSightings.unshift({
    id: nextId("sighting"),
    deviceId: normalizedDeviceId,
    scannerUserId: payload.scannerUserId ?? null,
    manufacturerId,
    packet: device.lastPacket,
    location: payload.location ?? null,
    rssi: payload.rssi ?? null,
    seenAt,
  });

  db.events.unshift({
    id: nextId("event"),
    deviceId: normalizedDeviceId,
    ownerUserId: device.ownerUserId,
    type: "SIGHTING_RECORDED",
    seenAt,
    packetType: device.lastPacket.packetTypeName,
    bagState: device.lastPacket.bagStateName,
    rssi: payload.rssi ?? null,
    location: payload.location ?? null,
  });

  evaluateRealtimeAlerts(db, device, previousState);
  return device;
}

/**
 * Evaluate and update bag-state, self-test health, and geofence alerts
 * immediately after a new sighting is recorded.
 *
 * - BAG_OPENED / BAG_CLOSED alerts are raised when the bag-state transitions.
 * - STATE_CHANGE_SIGNAL alerts fire when a STATE_CHANGE packet arrives without
 *   a detectable state transition (e.g. missed earlier packet).
 * - SELF_TEST_HEALTH alerts are raised/closed based on the health-status bits.
 * - GEOFENCE_EXIT alerts fire when the device moves outside all owner geofences.
 *
 * @param {object}      db            - In-memory database object (mutated in place).
 * @param {object}      device        - Device record (already updated with latest packet).
 * @param {number|null} previousState - Bag state from the prior sighting, or null.
 */
export function evaluateRealtimeAlerts(db, device, previousState = null) {
  if (!device.ownerUserId || !device.lastPacket) {
    return;
  }

  if (previousState !== null && previousState !== device.lastPacket.bagState) {
    if (device.lastPacket.bagState === 1) {
      closeAlert(db, "BAG_CLOSED", device.deviceId);
      createAlert(
        db,
        "BAG_OPENED",
        device,
        `${device.displayName} was opened. Previous state was ${bagStateName(previousState)}.`
      );
    } else {
      closeAlert(db, "BAG_OPENED", device.deviceId);
      createAlert(
        db,
        "BAG_CLOSED",
        device,
        `${device.displayName} was closed. Previous state was ${bagStateName(previousState)}.`
      );
    }
  } else if (device.lastPacket.packetType === PACKET_TYPES.STATE_CHANGE) {
    createAlert(
      db,
      "STATE_CHANGE_SIGNAL",
      device,
      `${device.displayName} reported a state-change packet. Latest reported state is ${device.lastPacket.bagStateName}.`
    );
  }

  if (device.lastPacket.packetType === PACKET_TYPES.SELF_TEST) {
    const health = parseHealthBits(device.lastPacket.healthStatus);
    if (health.reedFault || health.bootContradiction || health.adcFault) {
      createAlert(
        db,
        "SELF_TEST_HEALTH",
        device,
        `${device.displayName} self-test reported issues: ${JSON.stringify(health)}.`,
        { health }
      );
    } else {
      closeAlert(db, "SELF_TEST_HEALTH", device.deviceId);
    }
  }

  const ownerGeofences = db.geofences.filter(
    (entry) => entry.userId === device.ownerUserId && entry.enabled !== false
  );
  if (!ownerGeofences.length || !device.lastLocation) {
    return;
  }

  const previousGeofenceState = device.geofenceState ?? "unknown";
  const insideAny = ownerGeofences.some((geofence) => pointInGeofence(device.lastLocation, geofence));
  if (insideAny) {
    device.geofenceState = "inside";
    closeAlert(db, "GEOFENCE_EXIT", device.deviceId);
  } else {
    device.geofenceState = "outside";
    if (previousGeofenceState === "inside") {
      createAlert(
        db,
        "GEOFENCE_EXIT",
        device,
        `${device.displayName} left the configured geofence location.`
      );
    }
  }
}

/**
 * Periodically evaluate proximity alerts for all owned devices.
 *
 * Called every 5 seconds by the server's background interval. Raises a
 * PROXIMITY_LOST alert when a device has not been seen for more than
 * {@link STALE_SIGHTING_MS} milliseconds and closes it again once a fresh
 * sighting arrives. Uses `proximityAlertSentForSeenAt` to avoid re-raising
 * the same alert for an unchanged `lastSeenAt` timestamp.
 *
 * @param {object} db - In-memory database object (mutated in place).
 */
export function evaluateBackgroundAlerts(db) {
  const now = Date.now();

  for (const device of db.devices) {
    if (!device.ownerUserId || !device.lastSeenAt) {
      continue;
    }

    device.proximityMonitoringEnabled ??= true;
    device.proximityAlertSentForSeenAt ??= null;

    if (device.proximityMonitoringEnabled === false) {
      closeAlert(db, "PROXIMITY_LOST", device.deviceId);
      continue;
    }

    const ageMs = now - new Date(device.lastSeenAt).getTime();
    if (ageMs > STALE_SIGHTING_MS) {
      if (device.proximityAlertSentForSeenAt !== device.lastSeenAt) {
        createAlert(
          db,
          "PROXIMITY_LOST",
          device,
          `${device.displayName} has not been seen for more than 60 seconds.`,
          { ageMs }
        );
        device.proximityAlertSentForSeenAt = device.lastSeenAt;
      }
    } else {
      closeAlert(db, "PROXIMITY_LOST", device.deviceId);
    }
  }
}
