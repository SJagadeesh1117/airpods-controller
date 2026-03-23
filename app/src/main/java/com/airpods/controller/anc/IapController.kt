package com.airpods.controller.anc

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.airpods.controller.AncMode
import kotlinx.coroutines.*
import java.io.IOException
import java.util.UUID

/**
 * iAP2 / AACP (Apple Accessory Control Protocol) controller.
 *
 * How it works:
 *   AirPods expose an RFCOMM Bluetooth Classic channel that normally only Apple devices
 *   connect to. By opening a BluetoothSocket on the iAP2 UUID and sending the correct
 *   binary packets, we can impersonate an Apple device and issue commands like ANC toggle.
 *
 *   This is the same technique used by LibrePods (open source, reverse-engineered).
 *   Reference: https://github.com/kavishdevar/librepods
 *
 * Packet format (AACP / iAP2 encapsulated):
 *   [0xFF, 0x55]           - Sync bytes
 *   [length]               - Payload length
 *   [0x00]                 - Session ID
 *   [command bytes...]     - Command payload
 *   [checksum]             - XOR checksum of payload bytes
 *
 * ANC command payload:
 *   0x09 0x00 0x04 0x00 0x01 0xNN
 *   where 0xNN is the ANC mode byte from AncMode enum.
 */
class IapController(private val context: Context) {

    companion object {
        private const val TAG = "IapController"

        // Standard iAP2 RFCOMM UUID used by AirPods
        private val IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        // Sync header
        private val SYNC = byteArrayOf(0xFF.toByte(), 0x55.toByte())

        // ANC command template — last byte is mode
        private val ANC_CMD_PREFIX = byteArrayOf(
            0x09, 0x00, 0x04, 0x00, 0x01
        )

        private const val CONNECT_TIMEOUT_MS = 5000L
        private const val SEND_RETRY_COUNT = 3
    }

    private var socket: BluetoothSocket? = null
    private var iapJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onResult: ((success: Boolean, error: String?) -> Unit)? = null

    /**
     * Sends ANC mode change command to connected AirPods.
     * @param device  The paired BluetoothDevice for the AirPods
     * @param mode    The desired AncMode
     */
    fun sendAncMode(device: BluetoothDevice, mode: AncMode) {
        iapJob?.cancel()
        iapJob = scope.launch {
            var attempts = 0
            var lastError: String? = null
            while (attempts < SEND_RETRY_COUNT) {
                attempts++
                try {
                    Log.d(TAG, "ANC send attempt $attempts for mode: ${mode.displayName}")
                    connect(device)
                    val packet = buildAncPacket(mode.iap2Byte)
                    socket?.outputStream?.write(packet)
                    socket?.outputStream?.flush()
                    Log.d(TAG, "ANC packet sent: ${packet.toHex()}")
                    withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
                    disconnect()
                    return@launch
                } catch (e: IOException) {
                    lastError = e.message
                    Log.w(TAG, "ANC send attempt $attempts failed: ${e.message}")
                    disconnect()
                    delay(500)
                } catch (e: SecurityException) {
                    lastError = "Bluetooth permission denied"
                    Log.e(TAG, lastError!!)
                    break
                }
            }
            val err = lastError ?: "Unknown error"
            Log.e(TAG, "ANC send failed after $SEND_RETRY_COUNT attempts: $err")
            withContext(Dispatchers.Main) { onResult?.invoke(false, err) }
        }
    }

    private fun connect(device: BluetoothDevice) {
        disconnect()
        @Suppress("MissingPermission")
        socket = device.createRfcommSocketToServiceRecord(IAP2_UUID)
        @Suppress("MissingPermission")
        socket?.connect()
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    /**
     * Builds the full iAP2 packet for an ANC mode command.
     *
     * Packet layout:
     *   FF 55 [len] 00 [payload...] [checksum]
     */
    private fun buildAncPacket(modeByte: Byte): ByteArray {
        val payload = ANC_CMD_PREFIX + byteArrayOf(modeByte)
        val length = payload.size.toByte()
        val checksum = payload.fold(0) { acc, b -> acc xor b.toInt() }.toByte()
        return SYNC + byteArrayOf(length, 0x00) + payload + byteArrayOf(checksum)
    }

    fun release() {
        iapJob?.cancel()
        scope.cancel()
        disconnect()
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
}
