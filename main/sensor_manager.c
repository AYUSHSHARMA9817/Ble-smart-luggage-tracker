#include "sensor_manager.h"

#include <stdlib.h>
#include <string.h>

#include "driver/i2c.h"
#include "esp_log.h"

#include "aht20.h"
#include "bh1750.h"
#include "mpu6050.h"

#define TAG "SENSOR_MGR"

// Default I2C pins for many ESP32-C3 dev boards. Change as needed for your PCB.
#ifndef I2C_MASTER_SDA_IO
#define I2C_MASTER_SDA_IO GPIO_NUM_8
#endif

#ifndef I2C_MASTER_SCL_IO
#define I2C_MASTER_SCL_IO GPIO_NUM_9
#endif

#ifndef I2C_MASTER_PORT
#define I2C_MASTER_PORT I2C_NUM_0
#endif

#ifndef I2C_MASTER_FREQ_HZ
#define I2C_MASTER_FREQ_HZ 100000
#endif

typedef struct {
  bool i2c_ready;
  bool aht20_present;
  bool bh1750_present;
  uint8_t bh1750_addr;
  bool mpu6050_present;
  uint8_t mpu6050_addr;
  bool has_prev_accel;
  int16_t prev_accel_x_mg;
  int16_t prev_accel_y_mg;
  int16_t prev_accel_z_mg;
} sensor_state_t;

static sensor_state_t g_state = {0};

static esp_err_t i2c_init_once(void) {
  if (g_state.i2c_ready) {
    return ESP_OK;
  }

  i2c_config_t conf = {
      .mode = I2C_MODE_MASTER,
      .sda_io_num = I2C_MASTER_SDA_IO,
      .scl_io_num = I2C_MASTER_SCL_IO,
      .sda_pullup_en = GPIO_PULLUP_ENABLE,
      .scl_pullup_en = GPIO_PULLUP_ENABLE,
      .master = {.clk_speed = I2C_MASTER_FREQ_HZ},
      .clk_flags = 0,
  };

  esp_err_t err = i2c_param_config(I2C_MASTER_PORT, &conf);
  if (err != ESP_OK) {
    return err;
  }

  err = i2c_driver_install(I2C_MASTER_PORT, conf.mode, 0, 0, 0);
  if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
    return err;
  }

  g_state.i2c_ready = true;
  ESP_LOGI(TAG, "I2C ready (port=%d sda=%d scl=%d freq=%d)", I2C_MASTER_PORT,
           (int)I2C_MASTER_SDA_IO, (int)I2C_MASTER_SCL_IO,
           (int)I2C_MASTER_FREQ_HZ);
  return ESP_OK;
}

static bool i2c_probe_addr(uint8_t addr) {
  i2c_cmd_handle_t cmd = i2c_cmd_link_create();
  i2c_master_start(cmd);
  i2c_master_write_byte(cmd, (addr << 1) | I2C_MASTER_WRITE, true);
  i2c_master_stop(cmd);
  esp_err_t err = i2c_master_cmd_begin(I2C_MASTER_PORT, cmd, pdMS_TO_TICKS(40));
  i2c_cmd_link_delete(cmd);
  return err == ESP_OK;
}

esp_err_t sensor_manager_init(void) {
  esp_err_t err = i2c_init_once();
  if (err != ESP_OK) {
    ESP_LOGW(TAG, "I2C init failed: %s", esp_err_to_name(err));
    return err;
  }

  return sensor_manager_probe();
}

esp_err_t sensor_manager_probe(void) {
  esp_err_t err = i2c_init_once();
  if (err != ESP_OK) {
    return err;
  }

  g_state.aht20_present = i2c_probe_addr(AHT20_I2C_ADDR);
  if (g_state.aht20_present) {
    ESP_LOGI(TAG, "Detected AHT20 at 0x%02X", AHT20_I2C_ADDR);
    aht20_init(I2C_MASTER_PORT, AHT20_I2C_ADDR);
  }

  g_state.bh1750_present = false;
  g_state.bh1750_addr = 0;
  if (i2c_probe_addr(BH1750_ADDR_LOW)) {
    g_state.bh1750_present = true;
    g_state.bh1750_addr = BH1750_ADDR_LOW;
  } else if (i2c_probe_addr(BH1750_ADDR_HIGH)) {
    g_state.bh1750_present = true;
    g_state.bh1750_addr = BH1750_ADDR_HIGH;
  }
  if (g_state.bh1750_present) {
    ESP_LOGI(TAG, "Detected BH1750 at 0x%02X", g_state.bh1750_addr);
    bh1750_init(I2C_MASTER_PORT, g_state.bh1750_addr);
  }

  g_state.mpu6050_present = false;
  g_state.mpu6050_addr = 0;
  if (i2c_probe_addr(MPU6050_ADDR_LOW)) {
    g_state.mpu6050_present = true;
    g_state.mpu6050_addr = MPU6050_ADDR_LOW;
  } else if (i2c_probe_addr(MPU6050_ADDR_HIGH)) {
    g_state.mpu6050_present = true;
    g_state.mpu6050_addr = MPU6050_ADDR_HIGH;
  }
  if (g_state.mpu6050_present) {
    ESP_LOGI(TAG, "Detected MPU6050 at 0x%02X", g_state.mpu6050_addr);
    if (mpu6050_init(I2C_MASTER_PORT, g_state.mpu6050_addr) == ESP_OK) {
      g_state.has_prev_accel = false;
    }
  }

  if (!g_state.aht20_present && !g_state.bh1750_present &&
      !g_state.mpu6050_present) {
    ESP_LOGI(TAG, "No supported sensors detected on I2C");
  }

  return ESP_OK;
}

esp_err_t sensor_manager_read(sensor_readings_t *out) {
  if (!out) {
    return ESP_ERR_INVALID_ARG;
  }
  memset(out, 0, sizeof(*out));

  esp_err_t err = i2c_init_once();
  if (err != ESP_OK) {
    return err;
  }

  if (g_state.aht20_present) {
    int16_t temperature_c_x100 = 0;
    uint16_t humidity_rh_x100 = 0;
    if (aht20_read(I2C_MASTER_PORT, AHT20_I2C_ADDR, &temperature_c_x100,
                   &humidity_rh_x100) == ESP_OK) {
      out->has_temperature_c_x100 = true;
      out->temperature_c_x100 = temperature_c_x100;
      out->has_humidity_rh_x100 = true;
      out->humidity_rh_x100 = humidity_rh_x100;
    }
  }

  if (g_state.bh1750_present) {
    uint16_t lux = 0;
    if (bh1750_read_lux(I2C_MASTER_PORT, g_state.bh1750_addr, &lux) == ESP_OK) {
      out->has_lux = true;
      out->lux = lux;
    }
  }

  if (g_state.mpu6050_present) {
    mpu6050_reading_t motion = {0};
    if (mpu6050_read(I2C_MASTER_PORT, g_state.mpu6050_addr, &motion) ==
        ESP_OK) {
      out->has_accel_mg = true;
      out->accel_x_mg = motion.accel_x_mg;
      out->accel_y_mg = motion.accel_y_mg;
      out->accel_z_mg = motion.accel_z_mg;

      out->has_gyro_dps_x100 = true;
      out->gyro_x_dps_x100 = motion.gyro_x_dps_x100;
      out->gyro_y_dps_x100 = motion.gyro_y_dps_x100;
      out->gyro_z_dps_x100 = motion.gyro_z_dps_x100;

      if (g_state.has_prev_accel) {
        uint32_t delta =
            abs(motion.accel_x_mg - g_state.prev_accel_x_mg) +
            abs(motion.accel_y_mg - g_state.prev_accel_y_mg) +
            abs(motion.accel_z_mg - g_state.prev_accel_z_mg);
        out->vibration_score = delta > 65535U ? 65535U : (uint16_t)delta;
        out->has_vibration_score = true;
      }

      g_state.prev_accel_x_mg = motion.accel_x_mg;
      g_state.prev_accel_y_mg = motion.accel_y_mg;
      g_state.prev_accel_z_mg = motion.accel_z_mg;
      g_state.has_prev_accel = true;
    }
  }

  return ESP_OK;
}

static size_t tlv_write_u16le(uint8_t *buf, size_t max, uint8_t type,
                              uint16_t value) {
  if (max < 4) {
    return 0;
  }
  buf[0] = type;
  buf[1] = 2;
  buf[2] = (uint8_t)(value & 0xFF);
  buf[3] = (uint8_t)((value >> 8) & 0xFF);
  return 4;
}

static size_t tlv_write_i16le(uint8_t *buf, size_t max, uint8_t type,
                              int16_t value) {
  return tlv_write_u16le(buf, max, type, (uint16_t)value);
}

static size_t tlv_write_i16le_xyz(uint8_t *buf, size_t max, uint8_t type,
                                  int16_t x, int16_t y, int16_t z) {
  if (max < 8) {
    return 0;
  }
  buf[0] = type;
  buf[1] = 6;
  buf[2] = (uint8_t)((uint16_t)x & 0xFF);
  buf[3] = (uint8_t)(((uint16_t)x >> 8) & 0xFF);
  buf[4] = (uint8_t)((uint16_t)y & 0xFF);
  buf[5] = (uint8_t)(((uint16_t)y >> 8) & 0xFF);
  buf[6] = (uint8_t)((uint16_t)z & 0xFF);
  buf[7] = (uint8_t)(((uint16_t)z >> 8) & 0xFF);
  return 8;
}

size_t sensor_manager_encode_tlv(uint8_t *out_buf, size_t out_max,
                                 sensor_readings_t *out_readings) {
  if (!out_buf || out_max == 0) {
    return 0;
  }

  sensor_readings_t readings;
  sensor_readings_t *target = out_readings ? out_readings : &readings;
  if (sensor_manager_read(target) != ESP_OK) {
    return 0;
  }

  static uint8_t group = 0;
  size_t offset = 0;

  // Legacy BLE advertisements have tight payload limits. Rotate groups so
  // environmental, acceleration, and gyroscope telemetry all get airtime.
  for (uint8_t attempt = 0; attempt < 3 && offset == 0; attempt++) {
    uint8_t selected_group = (group + attempt) % 3;
    switch (selected_group) {
    case 0:
      if (target->has_temperature_c_x100) {
        offset += tlv_write_i16le(out_buf + offset, out_max - offset, 0x01,
                                  target->temperature_c_x100);
      }
      if (target->has_humidity_rh_x100 && offset < out_max) {
        offset += tlv_write_u16le(out_buf + offset, out_max - offset, 0x02,
                                  target->humidity_rh_x100);
      }
      if (target->has_lux && offset < out_max) {
        offset += tlv_write_u16le(out_buf + offset, out_max - offset, 0x03,
                                  target->lux);
      }
      break;
    case 1:
      if (target->has_accel_mg) {
        offset += tlv_write_i16le_xyz(out_buf + offset, out_max - offset, 0x10,
                                      target->accel_x_mg, target->accel_y_mg,
                                      target->accel_z_mg);
      }
      if (target->has_vibration_score && offset < out_max) {
        offset += tlv_write_u16le(out_buf + offset, out_max - offset, 0x12,
                                  target->vibration_score);
      }
      break;
    case 2:
      if (target->has_gyro_dps_x100) {
        offset += tlv_write_i16le_xyz(
            out_buf + offset, out_max - offset, 0x11,
            target->gyro_x_dps_x100, target->gyro_y_dps_x100,
            target->gyro_z_dps_x100);
      }
      if (target->has_vibration_score && offset < out_max) {
        offset += tlv_write_u16le(out_buf + offset, out_max - offset, 0x12,
                                  target->vibration_score);
      }
      break;
    }
  }

  group = (group + 1) % 3;

  return offset;
}
