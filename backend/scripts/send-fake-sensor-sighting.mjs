#!/usr/bin/env node

const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8787";
const deviceId = process.env.DEVICE_ID ?? "0x0000AB01";
const scannerUserId = process.env.SCANNER_USER_ID ?? "fake_scanner_cli";
const seqNum = Number(process.env.SEQ_NUM ?? Math.floor(Math.random() * 256));
const bagState = Number(process.env.BAG_STATE ?? 0);
const rssi = Number(process.env.RSSI ?? -62);

function round(value, digits = 2) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function jitter(base, range, digits = 2) {
  return round(base + (Math.random() * range * 2 - range), digits);
}

const payload = {
  scannerUserId,
  manufacturerId: "0xFF01",
  deviceId,
  rssi,
  location: {
    lat: Number(process.env.LAT ?? 26.1445),
    lng: Number(process.env.LNG ?? 91.7362),
    accuracyMeters: Number(process.env.ACCURACY_METERS ?? 18),
  },
  packet: {
    bagState,
    batteryLevel: Number(process.env.BATTERY_LEVEL ?? 3),
    seqNum,
    packetType: 3,
    healthStatus: 0,
    daysSinceChange: Number(process.env.DAYS_SINCE_CHANGE ?? 0),
  },
  sensors: {
    temperatureC: Number(process.env.TEMPERATURE_C ?? jitter(27.4, 2.0)),
    humidityRh: Number(process.env.HUMIDITY_RH ?? jitter(58.0, 8.0)),
    lux: Number(process.env.LUX ?? Math.round(jitter(340, 160, 0))),
    accelXMg: Number(process.env.ACCEL_X_MG ?? Math.round(jitter(12, 24, 0))),
    accelYMg: Number(process.env.ACCEL_Y_MG ?? Math.round(jitter(-18, 24, 0))),
    accelZMg: Number(process.env.ACCEL_Z_MG ?? Math.round(jitter(1000, 30, 0))),
    gyroXDps: Number(process.env.GYRO_X_DPS ?? jitter(0.18, 1.2)),
    gyroYDps: Number(process.env.GYRO_Y_DPS ?? jitter(-0.25, 1.2)),
    gyroZDps: Number(process.env.GYRO_Z_DPS ?? jitter(0.08, 1.2)),
    vibrationScore: Number(process.env.VIBRATION_SCORE ?? Math.round(jitter(42, 28, 0))),
  },
};

const response = await fetch(`${backendUrl}/api/sightings`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(payload),
});

const body = await response.text();
if (!response.ok) {
  console.error(`Fake sighting failed: HTTP ${response.status}`);
  console.error(body);
  process.exit(1);
}

console.log(`Sent fake SENSOR_TLV sighting for ${deviceId} seq=${seqNum}`);
console.log(body);
