package com.airpods.controller.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Passive BLE scanner that reads AirPods advertisement packets.
 *
 * AirPods broadcast a BLE advertisement with manufacturer data (company ID 0x004C = Apple).
 * The payload contains battery levels and ear detection state — no pairing needed to read it.
 *
 * Packet structure (Apple Proximity Beacon - AirPods variant, type 0x07):
 *   Byte 0    : 0x07 (AirPods type)
 *   Byte 1    : 0x19 (length)
 *   Byte 2    : Device model high byte
 *   Byte 3    : Device model low byte  (0x0E20 = AirPods Pro 2)
 *   Byte 4    : Status byte (charging flags, in-ear flags)
 *   Byte 5    : Battery right (high nibble) + Battery left (low nibble)   [or swapped if flipped]
 *   Byte 6    : Battery case (low nibble)
 *   Byte 7    : Lid state / in-ear flags
 *   ...
 *
 * References: OpenPods, GoodAncroid, LibrePods reverse engineering docs.
 */
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val APPLE_COMPANY_ID = 0x004C
        private const val AIRPODS_DATA_TYPE: Byte = 0x07
        private const val MIN_PAYLOAD_LENGTH = 25

        // Known AirPods Pro 2 model bytes
        private val AIRPODS_PRO2_MODEL = byteArrayOf(0x0E.toByte(), 0x20.toByte())

        // Mask bits for in-ear status in status byte
        private const val IN_EAR_LEFT_MASK  = 0x02
        private const val IN_EAR_RIGHT_MASK = 0x08
        private const val CHARGING_LEFT_MASK  = 0x01
        private const val CHARGING_RIGHT_MASK = 0x04
        private const val CHARGING_CASE_MASK  = 0x10
    }

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    var onAirPodsData: ((BleAirPodsData) -> Unit)? = null

    fun start() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable")
            return
        }

        // Filter only Apple manufacturer data to reduce battery drain
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(APPLE_COMPANY_ID, byteArrayOf())
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                parseAdvertisement(result)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
            }
        }

        try {
            scanner?.startScan(filters, settings, callback)
            Log.d(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission: ${e.message}")
        }
    }

    fun stop() {
        try {
            callback?.let { scanner?.stopScan(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Stop scan permission error: ${e.message}")
        }
        callback = null
        scanner = null
        Log.d(TAG, "BLE scan stopped")
    }

    private fun parseAdvertisement(result: ScanResult) {
        val mfData = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return

        // Must be at least minimum length and start with AirPods type byte
        if (mfData.size < MIN_PAYLOAD_LENGTH) return
        if (mfData[0] != AIRPODS_DATA_TYPE) return

        try {
            // Determine if left/right are flipped based on status byte bit 0
            val flipped = (mfData[4].toInt() and 0x02) == 0

            val rawRight = if (flipped) (mfData[5].toInt() and 0xF0) shr 4
                           else         (mfData[5].toInt() and 0x0F)
            val rawLeft  = if (flipped) (mfData[5].toInt() and 0x0F)
                           else         (mfData[5].toInt() and 0xF0) shr 4
            val rawCase  = mfData[6].toInt() and 0x0F

            // 15 = disconnected/unknown, otherwise multiply by ~11 to get rough %
            // Apple encodes in 10% steps: 0=0%, 1=10% ... 10=100%
            val battLeft  = if (rawLeft  == 15) -1 else (rawLeft  * 10).coerceIn(0, 100)
            val battRight = if (rawRight == 15) -1 else (rawRight * 10).coerceIn(0, 100)
            val battCase  = if (rawCase  == 15) -1 else (rawCase  * 10).coerceIn(0, 100)

            val statusByte = mfData[4].toInt()
            val inEarRight = if (flipped) (statusByte and IN_EAR_LEFT_MASK)  != 0
                             else         (statusByte and IN_EAR_RIGHT_MASK) != 0
            val inEarLeft  = if (flipped) (statusByte and IN_EAR_RIGHT_MASK) != 0
                             else         (statusByte and IN_EAR_LEFT_MASK)  != 0

            val chargingLeft  = (statusByte and CHARGING_LEFT_MASK)  != 0
            val chargingRight = (statusByte and CHARGING_RIGHT_MASK) != 0
            val chargingCase  = (statusByte and CHARGING_CASE_MASK)  != 0

            val data = BleAirPodsData(
                address       = result.device.address,
                rssi          = result.rssi,
                batteryLeft   = battLeft,
                batteryRight  = battRight,
                batteryCase   = battCase,
                inEarLeft     = inEarLeft,
                inEarRight    = inEarRight,
                chargingLeft  = chargingLeft,
                chargingRight = chargingRight,
                chargingCase  = chargingCase
            )

            Log.d(TAG, "AirPods data: $data")
            onAirPodsData?.invoke(data)

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }
}

data class BleAirPodsData(
    val address: String,
    val rssi: Int,
    val batteryLeft: Int,   // 0-100, -1 unknown
    val batteryRight: Int,
    val batteryCase: Int,
    val inEarLeft: Boolean,
    val inEarRight: Boolean,
    val chargingLeft: Boolean,
    val chargingRight: Boolean,
    val chargingCase: Boolean
)
