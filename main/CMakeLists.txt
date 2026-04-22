set(TRACKER_SOURCE "ble_tracker.c")
# set(TRACKER_SOURCE "ble_tracker_no.c")
# set(TRACKER_SOURCE "ble_tracker_nc.c")

idf_component_register(SRCS "${TRACKER_SOURCE}"
                       INCLUDE_DIRS ".")
