package com.airpods.controller.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.util.Log

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val APPLE_COMPANY_ID = 0x004C

        // AirPods type byte in Apple proximity payload
        private const val AIRPODS_TYPE: Byte = 0x07

        // Minimum usable payload size (bytes 0-6 must exist for battery/ear data)
        private const val MIN_PAYLOAD_LENGTH = 20

        // In-ear and charging bitmasks in status byte (byte 4)
        private const val IN_EAR_LEFT_MASK    = 0x02
        private const val IN_EAR_RIGHT_MASK   = 0x08
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
            Log.w(TAG, "BLE scanner unavailable (Bluetooth may be off)")
            return
        }

        // Use BALANCED mode — LOW_POWER is throttled aggressively on Samsung
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                parseAdvertisement(result)
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { parseAdvertisement(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed errorCode=$errorCode — retrying in 3s")
                // Restart after a short delay on failure
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stop()
                    start()
                }, 3000)
            }
        }

        try {
            // No manufacturer filter here — Samsung devices sometimes drop results
            // when the filter byte array is empty. We filter in parseAdvertisement() instead.
            scanner?.startScan(emptyList(), settings, callback)
            Log.d(TAG, "BLE scan started (BALANCED mode, no pre-filter)")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_SCAN permission missing: ${e.message}")
        }
    }

    fun stop() {
        try {
            callback?.let { scanner?.stopScan(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Stop scan error: ${e.message}")
        }
        callback = null
        scanner  = null
    }

    private fun parseAdvertisement(result: ScanResult) {
        // Only care about Apple manufacturer data
        val mfData = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return

        // Must be long enough and start with the AirPods proximity type byte (0x07)
        if (mfData.size < MIN_PAYLOAD_LENGTH) return
        if (mfData[0] != AIRPODS_TYPE) return

        try {
            // Bit 1 of status byte indicates which side is the primary (left/right flip)
            val flipped = (mfData[4].toInt() and 0x02) == 0

            val rawRight = if (flipped) (mfData[5].toInt() and 0xF0) ushr 4
                           else          mfData[5].toInt() and 0x0F
            val rawLeft  = if (flipped)  mfData[5].toInt() and 0x0F
                           else         (mfData[5].toInt() and 0xF0) ushr 4
            val rawCase  =               mfData[6].toInt() and 0x0F

            // 15 means unknown/disconnected; 0-10 maps to 0-100%
            fun rawToPercent(raw: Int) = if (raw == 15) -1 else (raw * 10).coerceIn(0, 100)

            val battLeft  = rawToPercent(rawLeft)
            val battRight = rawToPercent(rawRight)
            val battCase  = rawToPercent(rawCase)

            val status     = mfData[4].toInt() and 0xFF
            val inEarLeft  = if (flipped) (status and IN_EAR_RIGHT_MASK) != 0
                             else         (status and IN_EAR_LEFT_MASK)  != 0
            val inEarRight = if (flipped) (status and IN_EAR_LEFT_MASK)  != 0
                             else         (status and IN_EAR_RIGHT_MASK) != 0

            val chargingLeft  = (status and CHARGING_LEFT_MASK)  != 0
            val chargingRight = (status and CHARGING_RIGHT_MASK) != 0
            val chargingCase  = (status and CHARGING_CASE_MASK)  != 0

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

            Log.d(TAG, "AirPods L:$battLeft% R:$battRight% Case:$battCase% " +
                       "inEar=${inEarLeft}/${inEarRight} rssi=${result.rssi}")
            onAirPodsData?.invoke(data)

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }
}

data class BleAirPodsData(
    val address: String,
    val rssi: Int,
    val batteryLeft: Int,
    val batteryRight: Int,
    val batteryCase: Int,
    val inEarLeft: Boolean,
    val inEarRight: Boolean,
    val chargingLeft: Boolean,
    val chargingRight: Boolean,
    val chargingCase: Boolean
)
