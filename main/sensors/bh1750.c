#include "bh1750.h"

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

esp_err_t bh1750_init(i2c_port_t port, uint8_t addr) {
  // Power on (0x01), then start continuous high-res mode (0x10).
  const uint8_t power_on = 0x01;
  (void)i2c_write(port, addr, &power_on, 1);
  vTaskDelay(pdMS_TO_TICKS(10));
  return ESP_OK;
}

esp_err_t bh1750_read_lux(i2c_port_t port, uint8_t addr, uint16_t *out_lux) {
  if (!out_lux) {
    return ESP_ERR_INVALID_ARG;
  }

  const uint8_t mode = 0x10; // Continuous H-Resolution Mode
  esp_err_t err = i2c_write(port, addr, &mode, 1);
  if (err != ESP_OK) {
    return err;
  }

  vTaskDelay(pdMS_TO_TICKS(180));

  uint8_t raw[2] = {0};
  err = i2c_read(port, addr, raw, sizeof(raw));
  if (err != ESP_OK) {
    return err;
  }

  uint16_t value = ((uint16_t)raw[0] << 8) | raw[1];
  // lux = value / 1.2; approximate with integer math
  *out_lux = (uint16_t)((value * 10U) / 12U);
  return ESP_OK;
}

