#include "aht20.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static esp_err_t i2c_write(i2c_port_t port, uint8_t addr, const uint8_t *data,
                           size_t len) {
  return i2c_master_write_to_device(port, addr, data, len, pdMS_TO_TICKS(80));
}

static esp_err_t i2c_read(i2c_port_t port, uint8_t addr, uint8_t *data,
                          size_t len) {
  return i2c_master_read_from_device(port, addr, data, len, pdMS_TO_TICKS(80));
}

esp_err_t aht20_init(i2c_port_t port, uint8_t addr) {
  // Initialise / calibrate command (best effort)
  const uint8_t init_cmd[] = {0xBE, 0x08, 0x00};
  (void)i2c_write(port, addr, init_cmd, sizeof(init_cmd));
  vTaskDelay(pdMS_TO_TICKS(10));
  return ESP_OK;
}

esp_err_t aht20_read(i2c_port_t port, uint8_t addr, int16_t *out_temperature_c_x100,
                     uint16_t *out_humidity_rh_x100) {
  if (!out_temperature_c_x100 || !out_humidity_rh_x100) {
    return ESP_ERR_INVALID_ARG;
  }

  const uint8_t measure_cmd[] = {0xAC, 0x33, 0x00};
  esp_err_t err = i2c_write(port, addr, measure_cmd, sizeof(measure_cmd));
  if (err != ESP_OK) {
    return err;
  }

  vTaskDelay(pdMS_TO_TICKS(85));

  uint8_t raw[6] = {0};
  err = i2c_read(port, addr, raw, sizeof(raw));
  if (err != ESP_OK) {
    return err;
  }

  // raw[0] is status. Next 5 bytes contain 20-bit humidity + 20-bit temp.
  uint32_t humidity_raw = ((uint32_t)raw[1] << 16) | ((uint32_t)raw[2] << 8) | raw[3];
  humidity_raw >>= 4;
  uint32_t temp_raw = ((uint32_t)(raw[3] & 0x0F) << 16) | ((uint32_t)raw[4] << 8) | raw[5];

  // Convert to engineering units.
  // RH% = humidity_raw / 2^20 * 100
  // TempC = temp_raw / 2^20 * 200 - 50
  uint32_t humidity_rh_x100 = (humidity_raw * 10000U) / 1048576U;
  int32_t temp_c_x100 = ((int32_t)(temp_raw * 20000U) / 1048576U) - 5000;

  *out_humidity_rh_x100 = (uint16_t)(humidity_rh_x100 > 65535U ? 65535U : humidity_rh_x100);
  if (temp_c_x100 > 32767) {
    temp_c_x100 = 32767;
  } else if (temp_c_x100 < -32768) {
    temp_c_x100 = -32768;
  }
  *out_temperature_c_x100 = (int16_t)temp_c_x100;
  return ESP_OK;
}

