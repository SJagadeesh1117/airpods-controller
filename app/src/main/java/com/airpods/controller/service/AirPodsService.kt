package com.airpods.controller.service

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.location.Location
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

/**
 * Foreground service that runs continuously to:
 *   1. BLE-scan for AirPods advertisements (battery + ear detection)
 *   2. Detect ear removal → pause audio → optionally disconnect Bluetooth
 *   3. Log last-known GPS location for Find My Buds
 *   4. Broadcast state changes to MainActivity
 */
class AirPodsService : LifecycleService() {

    companion object {
        private const val TAG = "AirPodsService"
        const val NOTIF_ID = 1001
        const val ACTION_STATE_UPDATE    = "com.airpods.controller.STATE_UPDATE"
        const val ACTION_SET_ANC         = "com.airpods.controller.SET_ANC"
        const val ACTION_SET_AUTO_DISC   = "com.airpods.controller.SET_AUTO_DISC"
        const val ACTION_SET_EAR_DETECT  = "com.airpods.controller.SET_EAR_DETECT"
        const val ACTION_DISCONNECT      = "com.airpods.controller.DISCONNECT"
        const val ACTION_FIND_MY_PLAY    = "com.airpods.controller.FIND_MY_PLAY"
        const val ACTION_SET_GESTURE     = "com.airpods.controller.SET_GESTURE"
        const val EXTRA_ANC_MODE         = "anc_mode"
        const val EXTRA_ENABLED          = "enabled"
        const val EXTRA_FIND_TARGET      = "find_target"   // "LEFT", "RIGHT", "BOTH"
        const val EXTRA_GESTURE_TYPE     = "gesture_type"  // "LEFT_HOLD", "RIGHT_HOLD", "LEFT_DOUBLE", "RIGHT_DOUBLE"
        const val EXTRA_GESTURE_ACTION   = "gesture_action" // GestureAction ordinal

        // Debounce: how long both buds must be out before auto-disconnect fires
        private const val OUT_EAR_DEBOUNCE_MS = 3000L
    }

    private var state = AirPodsState()
    private lateinit var bleScanner: BleScanner
    private lateinit var iapController: IapController
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var outEarRunnable: Runnable? = null
    private var connectedDevice: BluetoothDevice? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        bleScanner   = BleScanner(this)
        iapController = IapController(this)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        setupBleScanner()
        startForegroundNotification()
        startLocationUpdates()
        registerBluetoothReceiver()
        resolveConnectedAirPods()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SET_ANC -> {
                val modeOrdinal = intent.getIntExtra(EXTRA_ANC_MODE, 0)
                val mode = AncMode.values()[modeOrdinal]
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
            ACTION_FIND_MY_PLAY -> {
                val target = intent.getStringExtra(EXTRA_FIND_TARGET) ?: "BOTH"
                playFindMySound(target)
            }
            ACTION_SET_GESTURE -> {
                val type   = intent?.getStringExtra(EXTRA_GESTURE_TYPE)
                val action = GestureAction.values()[intent?.getIntExtra(EXTRA_GESTURE_ACTION, 0) ?: 0]
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

        state = state.copy(
            isConnected  = true,
            deviceAddress = data.address,
            batteryLeft  = data.batteryLeft,
            batteryRight = data.batteryRight,
            batteryCase  = data.batteryCase,
            leftInEar    = data.inEarLeft,
            rightInEar   = data.inEarRight
        )

        // Ear detection logic
        if (state.earDetectionEnabled) {
            val bothOutNow   = !data.inEarLeft && !data.inEarRight
            val eitherWasIn  = wasLeftIn || wasRightIn

            if (bothOutNow && eitherWasIn) {
                // Start debounce timer — if still out after delay, trigger actions
                outEarRunnable?.let { handler.removeCallbacks(it) }
                outEarRunnable = Runnable {
                    onBothBudsRemoved()
                }.also { handler.postDelayed(it, OUT_EAR_DEBOUNCE_MS) }
            } else if (data.inEarLeft || data.inEarRight) {
                // Bud re-inserted — cancel pending disconnect
                outEarRunnable?.let { handler.removeCallbacks(it) }
                outEarRunnable = null
            }
        }

        updateNotification()
        broadcastState()
    }

    private fun onBothBudsRemoved() {
        Log.d(TAG, "Both buds removed — pausing audio")

        // Pause audio
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            )
        )

        // Auto-disconnect if enabled
        if (state.autoDisconnectEnabled) {
            Log.d(TAG, "Auto-disconnect triggered")
            disconnectBluetooth()
        }
    }

    // ─── ANC / iAP2 ───────────────────────────────────────────────────────────

    private fun handleSetAnc(mode: AncMode) {
        val device = connectedDevice
        if (device == null) {
            Log.w(TAG, "No connected AirPods device for ANC command")
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

    // ─── Bluetooth State ──────────────────────────────────────────────────────

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (isAirPods(device)) {
                        connectedDevice = device
                        state = state.copy(isConnected = true)
                        saveLastLocation()
                        broadcastState()
                        Log.d(TAG, "AirPods connected: ${device?.address}")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (isAirPods(device)) {
                        connectedDevice = null
                        state = state.copy(
                            isConnected  = false,
                            leftInEar    = false,
                            rightInEar   = false
                        )
                        broadcastState()
                        Log.d(TAG, "AirPods disconnected")
                    }
                }
            }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(btReceiver, filter)
    }

    private fun resolveConnectedAirPods() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            @Suppress("MissingPermission")
            bm.adapter?.bondedDevices?.firstOrNull { isAirPods(it) }?.let { device ->
                connectedDevice = device
                state = state.copy(isConnected = true, deviceAddress = device.address)
                broadcastState()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error resolving paired devices: ${e.message}")
        }
    }

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
                // Use BluetoothDevice reflection to call disconnect (no public API)
                val method = device.javaClass.getMethod("disconnect")
                method.invoke(device)
                Log.d(TAG, "Disconnect called on device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
            // Fallback: disable/enable adapter (last resort, more disruptive)
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
                        lastSeenTimestamp  = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun saveLastLocation() {
        try {
            fusedLocation.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    state = state.copy(
                        lastKnownLocation = it,
                        lastSeenTimestamp  = System.currentTimeMillis()
                    )
                    broadcastState()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun saveGesturePreference(type: String, action: GestureAction) {
        getSharedPreferences("gestures", MODE_PRIVATE).edit()
            .putInt(type, action.ordinal)
            .apply()
        Log.d(TAG, "Gesture saved: $type → ${action.displayName}")
    }

    private fun playFindMySound(target: String = "BOTH") {
        // Play a loud repeating tone via AudioManager
        val ringtoneUri = android.media.RingtoneManager.getDefaultUri(
            android.media.RingtoneManager.TYPE_ALARM
        )
        val ringtone = android.media.RingtoneManager.getRingtone(this, ringtoneUri)
        ringtone?.play()

        // Auto-stop after 30 seconds
        handler.postDelayed({ ringtone?.stop() }, 30_000L)

        // Vibrate phone as feedback
        val vibrator = getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }

        Log.d(TAG, "Find My sound playing — target: $target")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, AirPodsApp.CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AirPods Controller Active")
            .setContentText(buildNotifText())
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
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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
        return if (!state.isConnected) "Not connected"
        else {
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
            parts.joinToString(" · ")
        }
    }

    // ─── State Broadcast ──────────────────────────────────────────────────────

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE_UPDATE).apply {
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
            putExtra("adaptive",       state.adaptiveAudioEnabled)
            putExtra("conv_aware",     state.conversationAwareEnabled)
            putExtra("loud_reduction", state.loudSoundReductionEnabled)
            putExtra("last_lat",       state.lastKnownLocation?.latitude ?: 0.0)
            putExtra("last_lng",       state.lastKnownLocation?.longitude ?: 0.0)
            putExtra("last_seen",      state.lastSeenTimestamp)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
