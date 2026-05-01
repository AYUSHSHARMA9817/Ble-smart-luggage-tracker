#pragma once

#include <stdint.h>

#include "driver/i2c.h"
#include "esp_err.h"

#define MPU6050_ADDR_LOW 0x68
#define MPU6050_ADDR_HIGH 0x69

typedef struct {
  int16_t accel_x_mg;
  int16_t accel_y_mg;
  int16_t accel_z_mg;
  int16_t gyro_x_dps_x100;
  int16_t gyro_y_dps_x100;
  int16_t gyro_z_dps_x100;
} mpu6050_reading_t;

esp_err_t mpu6050_init(i2c_port_t port, uint8_t addr);
esp_err_t mpu6050_read(i2c_port_t port, uint8_t addr, mpu6050_reading_t *out);
