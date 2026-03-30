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
 * Sends ANC mode commands to AirPods via RFCOMM (Bluetooth Classic).
 *
 * AirPods expose an iAP2 RFCOMM channel. We open a socket, read any initial
 * bytes the AirPods send (device info / session init), then write the ANC packet.
 *
 * Packet layout: FF 55 LEN 00 CMD... CHECKSUM
 * ANC command:   09 00 04 00 01 [mode_byte]
 */
class IapController(private val context: Context) {

    companion object {
        private const val TAG = "IapController"

        // Primary iAP2 UUID used by AirPods over Bluetooth Classic
        private val UUID_IAP2_PRIMARY = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        // Fallback UUID used by some firmware versions
        private val UUID_IAP2_FALLBACK = UUID.fromString("74ec2172-0fad-4052-a2e1-1b1b58f2b86b")

        private val SYNC = byteArrayOf(0xFF.toByte(), 0x55.toByte())
        private val ANC_CMD = byteArrayOf(0x09, 0x00, 0x04, 0x00, 0x01)

        private const val CONNECT_TIMEOUT_MS = 6000L
        private const val READ_TIMEOUT_MS    = 1500L  // wait for AirPods init bytes
        private const val SEND_RETRY_COUNT   = 2
    }

    private var socket: BluetoothSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onResult: ((success: Boolean, error: String?) -> Unit)? = null

    fun sendAncMode(device: BluetoothDevice, mode: AncMode) {
        job?.cancel()
        job = scope.launch {
            val packet = buildPacket(mode.iap2Byte)
            var lastError = "Unknown error"

            // Try primary UUID first, then fallback
            for (uuid in listOf(UUID_IAP2_PRIMARY, UUID_IAP2_FALLBACK)) {
                repeat(SEND_RETRY_COUNT) attempt@{ attempt ->
                    try {
                        Log.d(TAG, "Attempt ${attempt + 1} uuid=$uuid mode=${mode.displayName}")
                        connect(device, uuid)

                        // Read any initial bytes the AirPods send (session negotiation).
                        // Without reading these, some firmware versions close the connection.
                        drainInitialResponse()

                        socket?.outputStream?.write(packet)
                        socket?.outputStream?.flush()
                        Log.d(TAG, "ANC packet sent: ${packet.toHex()}")

                        withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
                        disconnect()
                        return@launch   // success
                    } catch (e: IOException) {
                        lastError = e.message ?: "IO error"
                        Log.w(TAG, "Attempt failed ($uuid): $lastError")
                        disconnect()
                        delay(600)
                    } catch (e: SecurityException) {
                        lastError = "Bluetooth permission denied"
                        Log.e(TAG, lastError)
                        disconnect()
                        return@launch   // no point retrying a permission error
                    }
                }
            }

            Log.e(TAG, "All ANC attempts failed: $lastError")
            withContext(Dispatchers.Main) { onResult?.invoke(false, lastError) }
        }
    }

    @Suppress("MissingPermission")
    private fun connect(device: BluetoothDevice, uuid: UUID) {
        disconnect()
        socket = device.createRfcommSocketToServiceRecord(uuid)
        socket?.connect()
    }

    /**
     * Read and discard any bytes the AirPods send immediately after connection.
     * Times out after READ_TIMEOUT_MS so we don't block forever.
     */
    private fun drainInitialResponse() {
        val input = socket?.inputStream ?: return
        val buf   = ByteArray(256)
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val n = input.read(buf)
                    Log.d(TAG, "Initial response ($n bytes): ${buf.take(n).toByteArray().toHex()}")
                } else {
                    Thread.sleep(50)
                }
            }
        } catch (e: IOException) {
            // Socket closed or nothing to read — fine, proceed with sending
        }
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    private fun buildPacket(modeByte: Byte): ByteArray {
        val payload  = ANC_CMD + byteArrayOf(modeByte)
        val length   = payload.size.toByte()
        val checksum = payload.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte()
        return SYNC + byteArrayOf(length, 0x00) + payload + byteArrayOf(checksum)
    }

    fun release() {
        job?.cancel()
        scope.cancel()
        disconnect()
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }
}
