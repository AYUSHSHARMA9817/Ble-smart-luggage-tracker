package com.bletracker.app.data

fun alertTitle(type: String): String =
    when (type) {
        "BAG_OPENED" -> "Bag Opened"
        "BAG_CLOSED" -> "Bag Closed"
        "GEOFENCE_EXIT" -> "Bag Left Safe Zone"
        "GEOFENCE_ENTRY" -> "Bag Returned To Safe Zone"
        "PROXIMITY_LOST" -> "Tracker Out Of Contact"
        "PROXIMITY_RESTORED" -> "Tracker Contact Restored"
        "STATE_CHANGE_SIGNAL" -> "Tracker State Change"
        "SELF_TEST_HEALTH" -> "Tracker Health Warning"
        else -> type.replace('_', ' ')
    }
