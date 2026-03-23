package com.airpods.controller

import android.location.Location

/**
 * Central state model. All UI and service updates go through this.
 */
data class AirPodsState(
    // Connection
    val isConnected: Boolean = false,
    val deviceName: String = "AirPods Pro 2",
    val deviceAddress: String = "",

    // Battery (0-100, -1 = unknown)
    val batteryLeft: Int = -1,
    val batteryRight: Int = -1,
    val batteryCase: Int = -1,

    // Ear detection
    val leftInEar: Boolean = false,
    val rightInEar: Boolean = false,

    // ANC mode
    val ancMode: AncMode = AncMode.OFF,

    // Settings
    val autoDisconnectEnabled: Boolean = true,
    val earDetectionEnabled: Boolean = true,
    val adaptiveAudioEnabled: Boolean = true,
    val conversationAwareEnabled: Boolean = true,
    val loudSoundReductionEnabled: Boolean = false,

    // Gestures
    val leftHoldAction: GestureAction = GestureAction.NOISE_CONTROL,
    val rightHoldAction: GestureAction = GestureAction.NOISE_CONTROL,
    val leftDoubleTap: GestureAction = GestureAction.PLAY_PAUSE,
    val rightDoubleTap: GestureAction = GestureAction.PLAY_PAUSE,

    // Find My
    val lastKnownLocation: Location? = null,
    val lastSeenTimestamp: Long = 0L,
    val findMyActive: Boolean = false
)

enum class AncMode(val displayName: String, val iap2Byte: Byte) {
    OFF("Off", 0x01),
    NOISE_CANCEL("Noise Cancellation", 0x02),
    TRANSPARENCY("Transparency", 0x03),
    ADAPTIVE("Adaptive", 0x04)
}

enum class GestureAction(val displayName: String) {
    PLAY_PAUSE("Play / Pause"),
    NEXT_TRACK("Next Track"),
    PREV_TRACK("Previous Track"),
    NOISE_CONTROL("Cycle Noise Control"),
    SIRI("Siri / Voice Assistant"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down"),
    OFF("Off")
}
