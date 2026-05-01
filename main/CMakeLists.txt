set(TRACKER_SOURCE "ble_tracker.c")
# set(TRACKER_SOURCE "ble_tracker_no.c")
# set(TRACKER_SOURCE "ble_tracker_nc.c")

idf_component_register(
  SRCS
    "${TRACKER_SOURCE}"
    "sensor_manager.c"
    "sensors/aht20.c"
    "sensors/bh1750.c"
    "sensors/mpu6050.c"
  INCLUDE_DIRS
    "."
    "sensors"
)
