#include "mpu6050.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define MPU6050_REG_PWR_MGMT_1 0x6B
#define MPU6050_REG_ACCEL_CONFIG 0x1C
#define MPU6050_REG_GYRO_CONFIG 0x1B
#define MPU6050_REG_ACCEL_XOUT_H 0x3B

static esp_err_t write_reg(i2c_port_t port, uint8_t addr, uint8_t reg,
                           uint8_t value) {
  uint8_t data[] = {reg, value};
  return i2c_master_write_to_device(port, addr, data, sizeof(data),
                                    pdMS_TO_TICKS(80));
}

static esp_err_t read_regs(i2c_port_t port, uint8_t addr, uint8_t reg,
                           uint8_t *data, size_t len) {
  return i2c_master_write_read_device(port, addr, &reg, 1, data, len,
                                      pdMS_TO_TICKS(80));
}

static int16_t read_i16_be(const uint8_t *data) {
  return (int16_t)(((uint16_t)data[0] << 8) | data[1]);
}

esp_err_t mpu6050_init(i2c_port_t port, uint8_t addr) {
  esp_err_t err = write_reg(port, addr, MPU6050_REG_PWR_MGMT_1, 0x00);
  if (err != ESP_OK) {
    return err;
  }
  vTaskDelay(pdMS_TO_TICKS(20));

  // +/-2g accelerometer and +/-250 dps gyro for maximum sensitivity.
  err = write_reg(port, addr, MPU6050_REG_ACCEL_CONFIG, 0x00);
  if (err != ESP_OK) {
    return err;
  }
  return write_reg(port, addr, MPU6050_REG_GYRO_CONFIG, 0x00);
}

esp_err_t mpu6050_read(i2c_port_t port, uint8_t addr, mpu6050_reading_t *out) {
  if (!out) {
    return ESP_ERR_INVALID_ARG;
  }

  uint8_t raw[14] = {0};
  esp_err_t err = read_regs(port, addr, MPU6050_REG_ACCEL_XOUT_H, raw,
                            sizeof(raw));
  if (err != ESP_OK) {
    return err;
  }

  int16_t accel_x_raw = read_i16_be(&raw[0]);
  int16_t accel_y_raw = read_i16_be(&raw[2]);
  int16_t accel_z_raw = read_i16_be(&raw[4]);
  int16_t gyro_x_raw = read_i16_be(&raw[8]);
  int16_t gyro_y_raw = read_i16_be(&raw[10]);
  int16_t gyro_z_raw = read_i16_be(&raw[12]);

  out->accel_x_mg = (int16_t)((int32_t)accel_x_raw * 1000 / 16384);
  out->accel_y_mg = (int16_t)((int32_t)accel_y_raw * 1000 / 16384);
  out->accel_z_mg = (int16_t)((int32_t)accel_z_raw * 1000 / 16384);

  out->gyro_x_dps_x100 = (int16_t)((int32_t)gyro_x_raw * 10000 / 13100);
  out->gyro_y_dps_x100 = (int16_t)((int32_t)gyro_y_raw * 10000 / 13100);
  out->gyro_z_dps_x100 = (int16_t)((int32_t)gyro_z_raw * 10000 / 13100);

  return ESP_OK;
}
