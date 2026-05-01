#pragma once

#include <stdint.h>

#include "driver/i2c.h"
#include "esp_err.h"

#define BH1750_ADDR_LOW 0x23
#define BH1750_ADDR_HIGH 0x5C

esp_err_t bh1750_init(i2c_port_t port, uint8_t addr);
esp_err_t bh1750_read_lux(i2c_port_t port, uint8_t addr, uint16_t *out_lux);

