# Multifunction Sensor Expansion Plan

This tracker keeps the reed switch as the luggage open/closed sensor and adds optional plug-in modules around it.

## Board Connector Strategy

Your board has 5V, GND, 3.3V, and 13 GPIO pins. For a startup-ready product, expose these as predictable ports instead of loose pins:

- I2C sensor port: 3.3V, GND, SDA, SCL
- Digital event port: 3.3V, GND, GPIO input, optional interrupt line
- Power-only 5V port: for modules that need 5V power but still use 3.3V logic
- Expansion header: remaining GPIO pins for UART/SPI/prototype modules

The firmware currently uses I2C for auto-detection. Default pins are `GPIO8` for SDA and `GPIO9` for SCL in `main/sensor_manager.c`.

## Supported Firmware Sensors

Current auto-detected I2C modules:

- AHT20 (`0x38`): temperature and humidity
- BH1750 (`0x23` or `0x5C`): ambient light
- MPU6050 (`0x68` or `0x69`): accelerometer, gyroscope, vibration score

The reed switch remains on its existing GPIO and is not part of the plug-in sensor registry.

## Sensor Categories

Thermostat and temperature:

- Use AHT20 for temperature/humidity sensing.
- If you need thermostat control, add a separate relay/MOSFET output driver and backend rules such as `temperatureC > threshold`.

Gyroscope and motion:

- Use MPU6050 first because it is cheap, common, and easy to source.
- The firmware advertises acceleration in mg and gyro in degrees/second.

Vibration:

- Use the MPU6050 acceleration delta as the first vibration score.
- For very low-cost shock detection, add a digital vibration module later on the digital event port.

Camera:

- Do not stream camera data over BLE advertisements.
- ESP32-C3 is not a good camera host for image capture; use an ESP32-S3 camera module or an external camera board.
- Treat camera as a separate device that sends image events or URLs to the backend, while this tracker continues to broadcast BLE identity and sensor telemetry.

## BLE Telemetry Format

The existing 10-byte luggage tracker packet is unchanged. Optional sensor readings are appended as TLV fields when `packetType = 3`.

TLV fields:

- `0x01`: temperature, `int16`, Celsius x 100
- `0x02`: humidity, `uint16`, percent RH x 100
- `0x03`: light, `uint16`, lux
- `0x10`: acceleration, `int16 x/y/z`, mg
- `0x11`: gyroscope, `int16 x/y/z`, degrees/second x 100
- `0x12`: vibration score, `uint16`

BLE legacy advertisements have a strict size limit, so the firmware rotates groups of readings across sensor advertisements.

## Product Direction

For an MVP, ship one core luggage board with:

- Reed switch for bag open/closed
- I2C port for environmental and motion modules
- MPU6050 on the default add-on module
- AHT20 on the default add-on module
- Optional BH1750 only when light sensing is part of the use case

For camera use cases, build a second SKU based on ESP32-S3 with camera hardware and let both devices share the same backend owner account and device model.
