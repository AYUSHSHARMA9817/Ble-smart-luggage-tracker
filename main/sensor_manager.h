#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

typedef struct {
  bool has_temperature_c_x100;
  int16_t temperature_c_x100;

  bool has_humidity_rh_x100;
  uint16_t humidity_rh_x100;

  bool has_lux;
  uint16_t lux;

  bool has_accel_mg;
  int16_t accel_x_mg;
  int16_t accel_y_mg;
  int16_t accel_z_mg;

  bool has_gyro_dps_x100;
  int16_t gyro_x_dps_x100;
  int16_t gyro_y_dps_x100;
  int16_t gyro_z_dps_x100;

  bool has_vibration_score;
  uint16_t vibration_score;
} sensor_readings_t;

/**
 * Initialise the I2C bus and probe for supported sensors.
 *
 * Supported auto-detect sensors (I2C):
 * - AHT20 (0x38): temperature + humidity
 * - BH1750 (0x23 or 0x5C): ambient light (lux)
 * - MPU6050 (0x68 or 0x69): accelerometer, gyroscope, vibration score
 */
esp_err_t sensor_manager_init(void);

/** Re-scan the I2C bus for supported sensors (hot-plug best effort). */
esp_err_t sensor_manager_probe(void);

/** Read the latest values from any detected sensors. */
esp_err_t sensor_manager_read(sensor_readings_t *out);

/**
 * Encode sensor readings as TLV into @p out_buf.
 *
 * TLV format:
 * - type (1 byte)
 * - len  (1 byte)
 * - value (len bytes)
 *
 * Types:
 * - 0x01: temperature_c_x100 (int16 LE)
 * - 0x02: humidity_rh_x100   (uint16 LE)
 * - 0x03: lux                (uint16 LE)
 * - 0x10: accel_mg_xyz       (int16 LE x/y/z)
 * - 0x11: gyro_dps_x100_xyz  (int16 LE x/y/z)
 * - 0x12: vibration_score    (uint16 LE)
 *
 * @returns number of bytes written to out_buf.
 */
size_t sensor_manager_encode_tlv(uint8_t *out_buf, size_t out_max,
                                 sensor_readings_t *out_readings);
