package com.airpods.controller.service

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.media.AudioManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.airpods.controller.*
import com.airpods.controller.anc.IapController
import com.airpods.controller.ble.BleAirPodsData
import com.airpods.controller.ble.BleScanner
import com.airpods.controller.ui.MainActivity
import com.google.android.gms.location.*

class AirPodsService : LifecycleService() {

    companion object {
        private const val TAG = "AirPodsService"
        const val NOTIF_ID = 1001
        const val ACTION_STATE_UPDATE   = "com.airpods.controller.STATE_UPDATE"
        const val ACTION_SET_ANC        = "com.airpods.controller.SET_ANC"
        const val ACTION_SET_AUTO_DISC  = "com.airpods.controller.SET_AUTO_DISC"
        const val ACTION_SET_EAR_DETECT = "com.airpods.controller.SET_EAR_DETECT"
        const val ACTION_DISCONNECT     = "com.airpods.controller.DISCONNECT"
        const val ACTION_FIND_MY_PLAY   = "com.airpods.controller.FIND_MY_PLAY"
        const val ACTION_SET_GESTURE    = "com.airpods.controller.SET_GESTURE"
        const val EXTRA_ANC_MODE        = "anc_mode"
        const val EXTRA_ENABLED         = "enabled"
        const val EXTRA_FIND_TARGET     = "find_target"
        const val EXTRA_GESTURE_TYPE    = "gesture_type"
        const val EXTRA_GESTURE_ACTION  = "gesture_action"

        private const val OUT_EAR_DEBOUNCE_MS = 3000L
        // If no BLE advertisement is seen for this long, mark as out of range
        private const val BLE_TIMEOUT_MS = 30_000L
    }

    private var state = AirPodsState()
    private lateinit var bleScanner: BleScanner
    private lateinit var iapController: IapController
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var outEarRunnable: Runnable? = null
    private var connectedDevice: BluetoothDevice? = null

    // Fires when no BLE advertisement is received for BLE_TIMEOUT_MS
    private val bleTimeoutRunnable = Runnable {
        Log.d(TAG, "BLE timeout — AirPods out of range")
        state = state.copy(
            isConnected  = false,
            batteryLeft  = -1,
            batteryRight = -1,
            batteryCase  = -1,
            leftInEar    = false,
            rightInEar   = false
        )
        updateNotification()
        broadcastState()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager  = getSystemService(AudioManager::class.java)
        bleScanner    = BleScanner(this)
        iapController = IapController(this)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        startForegroundNotification()
        registerBluetoothReceiver()
        resolveConnectedAirPods()   // check if already connected via BT Classic
        setupBleScanner()
        startLocationUpdates()
        loadGesturePreferences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SET_ANC -> {
                val mode = AncMode.values()[intent.getIntExtra(EXTRA_ANC_MODE, 0)]
                handleSetAnc(mode)
            }
            ACTION_SET_AUTO_DISC -> {
                state = state.copy(autoDisconnectEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true))
                broadcastState()
            }
            ACTION_SET_EAR_DETECT -> {
                state = state.copy(earDetectionEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true))
                broadcastState()
            }
            ACTION_DISCONNECT -> disconnectBluetooth()
            ACTION_FIND_MY_PLAY -> playFindMySound(intent.getStringExtra(EXTRA_FIND_TARGET) ?: "BOTH")
            ACTION_SET_GESTURE -> {
                val type   = intent.getStringExtra(EXTRA_GESTURE_TYPE)
                val action = GestureAction.values()[intent.getIntExtra(EXTRA_GESTURE_ACTION, 0)]
                if (type != null) saveGesturePreference(type, action)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stop()
        iapController.release()
        unregisterReceiver(btReceiver)
        handler.removeCallbacksAndMessages(null)
    }

    // ─── BLE Scanner ──────────────────────────────────────────────────────────

    private fun setupBleScanner() {
        bleScanner.onAirPodsData = { data ->
            handler.post { handleBleData(data) }
        }
        bleScanner.start()
    }

    private fun handleBleData(data: BleAirPodsData) {
        val wasLeftIn  = state.leftInEar
        val wasRightIn = state.rightInEar

        // Reset the out-of-range timeout every time we see a fresh advertisement
        handler.removeCallbacks(bleTimeoutRunnable)
        handler.postDelayed(bleTimeoutRunnable, BLE_TIMEOUT_MS)

        // BLE advertisement = AirPods are nearby and broadcasting.
        // Mark as connected and update battery/ear data.
        state = state.copy(
            isConnected  = true,
            deviceAddress = data.address,
            batteryLeft  = data.batteryLeft,
            batteryRight = data.batteryRight,
            batteryCase  = data.batteryCase,
            leftInEar    = data.inEarLeft,
            rightInEar   = data.inEarRight
        )

        if (state.earDetectionEnabled) {
            val bothOutNow  = !data.inEarLeft && !data.inEarRight
            val eitherWasIn = wasLeftIn || wasRightIn

            if (bothOutNow && eitherWasIn) {
                outEarRunnable?.let { handler.removeCallbacks(it) }
                outEarRunnable = Runnable { onBothBudsRemoved() }
                    .also { handler.postDelayed(it, OUT_EAR_DEBOUNCE_MS) }
            } else if (data.inEarLeft || data.inEarRight) {
                outEarRunnable?.let { handler.removeCallbacks(it) }
                outEarRunnable = null
            }
        }

        updateNotification()
        broadcastState()
    }

    private fun onBothBudsRemoved() {
        Log.d(TAG, "Both buds removed — pausing audio")
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
        )
        if (state.autoDisconnectEnabled) {
            Log.d(TAG, "Auto-disconnect triggered")
            disconnectBluetooth()
        }
    }

    // ─── ANC / iAP2 ───────────────────────────────────────────────────────────

    private fun handleSetAnc(mode: AncMode) {
        val device = connectedDevice
        if (device == null) {
            Log.w(TAG, "ANC: no BT Classic connection — command skipped")
            return
        }
        iapController.onResult = { success, error ->
            if (success) {
                state = state.copy(ancMode = mode)
                broadcastState()
                updateNotification()
            } else {
                Log.e(TAG, "ANC command failed: $error")
            }
        }
        iapController.sendAncMode(device, mode)
    }

    // ─── Bluetooth Classic state ───────────────────────────────────────────────

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (!isAirPods(device)) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    connectedDevice = device
                    state = state.copy(
                        isConnected  = true,
                        deviceAddress = device!!.address,
                        deviceName   = getDeviceName(device)
                    )
                    // Cancel BLE timeout — we have a real BT connection now
                    handler.removeCallbacks(bleTimeoutRunnable)
                    // Try to read battery immediately via hidden API (Android 13+)
                    val initialBattery = readBatteryLevel(device)
                    if (initialBattery >= 0) {
                        state = state.copy(batteryLeft = initialBattery, batteryRight = initialBattery)
                    }
                    saveLastLocation()
                    updateNotification()
                    broadcastState()
                    Log.d(TAG, "AirPods BT connected: ${device.address}")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connectedDevice = null
                    handler.removeCallbacks(bleTimeoutRunnable)
                    state = state.copy(
                        isConnected  = false,
                        batteryLeft  = -1,
                        batteryRight = -1,
                        batteryCase  = -1,
                        leftInEar    = false,
                        rightInEar   = false
                    )
                    updateNotification()
                    broadcastState()
                    Log.d(TAG, "AirPods BT disconnected")
                }
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                    if (isAirPods(device)) {
                        val level = intent.getIntExtra(
                            "android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                        if (level >= 0) {
                            Log.d(TAG, "BT battery update: $level%")
                            // Only use BT battery if BLE hasn't given us per-bud levels
                            val left  = if (state.batteryLeft  >= 0) state.batteryLeft  else level
                            val right = if (state.batteryRight >= 0) state.batteryRight else level
                            state = state.copy(batteryLeft = left, batteryRight = right)
                            updateNotification()
                            broadcastState()
                        }
                    }
                }
            }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            // Battery level reports from BT stack (works when AirPods connected via HFP/A2DP)
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        }
        registerReceiver(btReceiver, filter)
    }

    /**
     * On startup, check if AirPods are already connected via Bluetooth Classic.
     * Uses reflection to check actual connection state (not just bonded/paired).
     */
    private fun resolveConnectedAirPods() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            @Suppress("MissingPermission")
            bm.adapter?.bondedDevices
                ?.filter { isAirPods(it) }
                ?.firstOrNull { isDeviceActuallyConnected(it) }
                ?.let { device ->
                    connectedDevice = device
                    state = state.copy(
                        isConnected  = true,
                        deviceAddress = device.address,
                        deviceName   = getDeviceName(device)
                    )
                    broadcastState()
                    Log.d(TAG, "AirPods already connected: ${device.address}")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error: ${e.message}")
        }
    }

    /** Reflection-based check for actual BT connection state (works on Android 8+). */
    private fun isDeviceActuallyConnected(device: BluetoothDevice): Boolean {
        return try {
            @Suppress("MissingPermission")
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    /** Read battery level via hidden API getBatteryLevel() — works on Android 13+. Returns -1 if unavailable. */
    private fun readBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            (method.invoke(device) as Int)
        } catch (e: Exception) {
            -1
        }
    }

    @Suppress("MissingPermission")
    private fun getDeviceName(device: BluetoothDevice): String =
        try { device.name ?: "AirPods" } catch (_: SecurityException) { "AirPods" }

    @Suppress("MissingPermission")
    private fun isAirPods(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return try {
            val name = device.name?.lowercase() ?: ""
            name.contains("airpods") || name.contains("beats")
        } catch (_: SecurityException) { false }
    }

    @Suppress("MissingPermission")
    private fun disconnectBluetooth() {
        try {
            connectedDevice?.let { device ->
                val method = device.javaClass.getMethod("disconnect")
                method.invoke(device)
                Log.d(TAG, "Disconnect called")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }

    // ─── Gesture handling ─────────────────────────────────────────────────────

    private fun loadGesturePreferences() {
        val prefs = getSharedPreferences("gestures", MODE_PRIVATE)
        state = state.copy(
            leftHoldAction  = GestureAction.values()[prefs.getInt("LEFT_HOLD",    GestureAction.NOISE_CONTROL.ordinal)],
            rightHoldAction = GestureAction.values()[prefs.getInt("RIGHT_HOLD",   GestureAction.NOISE_CONTROL.ordinal)],
            leftDoubleTap   = GestureAction.values()[prefs.getInt("LEFT_DOUBLE",  GestureAction.PLAY_PAUSE.ordinal)],
            rightDoubleTap  = GestureAction.values()[prefs.getInt("RIGHT_DOUBLE", GestureAction.PLAY_PAUSE.ordinal)]
        )
    }

    private fun saveGesturePreference(type: String, action: GestureAction) {
        getSharedPreferences("gestures", MODE_PRIVATE).edit()
            .putInt(type, action.ordinal).apply()
        state = when (type) {
            "LEFT_HOLD"    -> state.copy(leftHoldAction  = action)
            "RIGHT_HOLD"   -> state.copy(rightHoldAction = action)
            "LEFT_DOUBLE"  -> state.copy(leftDoubleTap   = action)
            "RIGHT_DOUBLE" -> state.copy(rightDoubleTap  = action)
            else           -> state
        }
    }

    fun dispatchGestureAction(action: GestureAction) {
        when (action) {
            GestureAction.PLAY_PAUSE    -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            GestureAction.NEXT_TRACK    -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            GestureAction.PREV_TRACK    -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            GestureAction.NOISE_CONTROL -> cycleAncMode()
            GestureAction.SIRI         -> launchVoiceAssistant()
            GestureAction.VOLUME_UP    -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_VOLUME_UP)
            GestureAction.VOLUME_DOWN  -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
            GestureAction.OFF          -> { /* no-op */ }
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    private fun cycleAncMode() {
        val modes = AncMode.values()
        handleSetAnc(modes[(state.ancMode.ordinal + 1) % modes.size])
    }

    private fun launchVoiceAssistant() {
        try {
            startActivity(Intent("android.intent.action.ASSIST")
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch voice assistant: ${e.message}")
        }
    }

    // ─── Find My Buds ─────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(10_000L)
            .build()
        try {
            fusedLocation.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                if (state.isConnected) {
                    state = state.copy(
                        lastKnownLocation = loc,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun saveLastLocation() {
        try {
            fusedLocation.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    state = state.copy(lastKnownLocation = it, lastSeenTimestamp = System.currentTimeMillis())
                    broadcastState()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun playFindMySound(target: String = "BOTH") {
        val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        val ringtone = android.media.RingtoneManager.getRingtone(this, ringtoneUri)
        ringtone?.play()
        handler.postDelayed({ ringtone?.stop() }, 30_000L)

        val vibrator = getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
        Log.d(TAG, "Find My sound — target: $target")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, AirPodsApp.CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AirPods Controller")
            .setContentText("Scanning for AirPods…")
            .setContentIntent(intent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, AirPodsApp.CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AirPods Controller")
            .setContentText(buildNotifText())
            .setContentIntent(intent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun buildNotifText(): String {
        if (!state.isConnected) return "Not connected — scanning…"
        val parts = mutableListOf<String>()
        if (state.batteryLeft  >= 0) parts.add("L:${state.batteryLeft}%")
        if (state.batteryRight >= 0) parts.add("R:${state.batteryRight}%")
        if (state.batteryCase  >= 0) parts.add("Case:${state.batteryCase}%")
        val earStr = when {
            state.leftInEar && state.rightInEar -> "Both in ear"
            state.leftInEar  -> "Left in ear"
            state.rightInEar -> "Right in ear"
            else             -> "Not in ear"
        }
        parts.add(earStr)
        return parts.joinToString(" · ")
    }

    // ─── State Broadcast ──────────────────────────────────────────────────────

    private fun broadcastState() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_STATE_UPDATE).apply {
                putExtra("connected",      state.isConnected)
                putExtra("device_name",    state.deviceName)
                putExtra("battery_left",   state.batteryLeft)
                putExtra("battery_right",  state.batteryRight)
                putExtra("battery_case",   state.batteryCase)
                putExtra("in_ear_left",    state.leftInEar)
                putExtra("in_ear_right",   state.rightInEar)
                putExtra("anc_mode",       state.ancMode.ordinal)
                putExtra("auto_disc",      state.autoDisconnectEnabled)
                putExtra("ear_detect",     state.earDetectionEnabled)
                putExtra("last_lat",       state.lastKnownLocation?.latitude ?: 0.0)
                putExtra("last_lng",       state.lastKnownLocation?.longitude ?: 0.0)
                putExtra("last_seen",      state.lastSeenTimestamp)
            }
        )
    }
}
