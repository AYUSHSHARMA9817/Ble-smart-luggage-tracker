#pragma once

#include <stdint.h>

#include "driver/i2c.h"
#include "esp_err.h"

#define AHT20_I2C_ADDR 0x38

esp_err_t aht20_init(i2c_port_t port, uint8_t addr);

/**
 * Read AHT20 sensor.
 *
 * @param out_temperature_c_x100 Temperature in celsius * 100.
 * @param out_humidity_rh_x100 Humidity in %RH * 100.
 */
esp_err_t aht20_read(i2c_port_t port, uint8_t addr, int16_t *out_temperature_c_x100,
                     uint16_t *out_humidity_rh_x100);

