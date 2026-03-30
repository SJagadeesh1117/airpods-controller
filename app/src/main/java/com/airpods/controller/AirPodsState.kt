package com.airpods.controller

import android.location.Location

data class AirPodsState(
    val isConnected: Boolean = false,
    val deviceName: String = "AirPods Pro 2",
    val deviceAddress: String = "",

    val batteryLeft: Int = -1,
    val batteryRight: Int = -1,
    val batteryCase: Int = -1,

    val leftInEar: Boolean = false,
    val rightInEar: Boolean = false,

    val autoDisconnectEnabled: Boolean = true,
    val earDetectionEnabled: Boolean = true,

    val doubleTapAction: GestureAction = GestureAction.PLAY_PAUSE,

    val lastKnownLocation: Location? = null,
    val lastSeenTimestamp: Long = 0L
)

enum class GestureAction(val displayName: String) {
    PLAY_PAUSE("Play / Pause"),
    NEXT_TRACK("Next Track"),
    PREV_TRACK("Previous Track"),
    VOICE_ASSISTANT("Voice Assistant"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down"),
    OFF("Off")
}
