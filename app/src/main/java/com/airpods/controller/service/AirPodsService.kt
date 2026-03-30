package com.airpods.controller.service

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.airpods.controller.*
import com.airpods.controller.ble.BleAirPodsData
import com.airpods.controller.ble.BleScanner
import com.airpods.controller.ui.MainActivity
import com.google.android.gms.location.*

class AirPodsService : LifecycleService() {

    companion object {
        private const val TAG = "AirPodsService"
        const val NOTIF_ID              = 1001
        const val ACTION_STATE_UPDATE   = "com.airpods.controller.STATE_UPDATE"
        const val ACTION_SET_AUTO_DISC  = "com.airpods.controller.SET_AUTO_DISC"
        const val ACTION_SET_EAR_DETECT = "com.airpods.controller.SET_EAR_DETECT"
        const val ACTION_DISCONNECT     = "com.airpods.controller.DISCONNECT"
        const val ACTION_FIND_MY_PLAY   = "com.airpods.controller.FIND_MY_PLAY"
        const val ACTION_SET_GESTURE    = "com.airpods.controller.SET_GESTURE"
        const val EXTRA_ENABLED         = "enabled"
        const val EXTRA_GESTURE_ACTION  = "gesture_action"

        private const val OUT_EAR_DEBOUNCE_MS = 3000L
        private const val BLE_TIMEOUT_MS      = 30_000L
    }

    private var state = AirPodsState()
    private lateinit var bleScanner: BleScanner
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private var outEarRunnable: Runnable? = null
    private var connectedDevice: BluetoothDevice? = null
    private var findMyPlayer: MediaPlayer? = null

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
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        startForegroundNotification()
        setupMediaSession()
        registerBluetoothReceiver()
        resolveConnectedAirPods()
        setupBleScanner()
        startLocationUpdates()
        loadGesturePreferences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SET_AUTO_DISC -> {
                state = state.copy(autoDisconnectEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true))
                broadcastState()
            }
            ACTION_SET_EAR_DETECT -> {
                state = state.copy(earDetectionEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true))
                broadcastState()
            }
            ACTION_DISCONNECT  -> disconnectBluetooth()
            ACTION_FIND_MY_PLAY -> playFindMySound()
            ACTION_SET_GESTURE -> {
                val action = GestureAction.values()[intent.getIntExtra(EXTRA_GESTURE_ACTION, 0)]
                saveGesturePreference(action)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stop()
        mediaSession.release()
        unregisterReceiver(btReceiver)
        handler.removeCallbacksAndMessages(null)
        findMyPlayer?.release()
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    // ─── MediaSession (intercept AirPods double-tap) ──────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "AirPodsController")
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return false

                if (event.action != KeyEvent.ACTION_DOWN) return false
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (state.doubleTapAction != GestureAction.PLAY_PAUSE) {
                            dispatchGestureAction(state.doubleTapAction)
                            true  // consumed — don't pass to music apps
                        } else false
                    }
                    else -> false
                }
            }
        })
        mediaSession.isActive = true
    }

    // ─── BLE Scanner ──────────────────────────────────────────────────────────

    private fun setupBleScanner() {
        bleScanner.onAirPodsData = { data -> handler.post { handleBleData(data) } }
        bleScanner.start()
    }

    private fun handleBleData(data: BleAirPodsData) {
        val wasLeftIn  = state.leftInEar
        val wasRightIn = state.rightInEar

        handler.removeCallbacks(bleTimeoutRunnable)
        handler.postDelayed(bleTimeoutRunnable, BLE_TIMEOUT_MS)

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
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        if (state.autoDisconnectEnabled) {
            Log.d(TAG, "Auto-disconnect triggered")
            disconnectBluetooth()
        }
    }

    // ─── Bluetooth Classic state ───────────────────────────────────────────────

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (!isAirPods(device)) return
                    connectedDevice = device
                    val battery = readBatteryLevel(device!!)
                    state = state.copy(
                        isConnected   = true,
                        deviceAddress = device.address,
                        deviceName    = getDeviceName(device),
                        batteryLeft   = if (battery >= 0) battery else state.batteryLeft,
                        batteryRight  = if (battery >= 0) battery else state.batteryRight
                    )
                    handler.removeCallbacks(bleTimeoutRunnable)
                    saveLastLocation()
                    updateNotification()
                    broadcastState()
                    Log.d(TAG, "AirPods connected: ${device.address} battery=$battery")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (!isAirPods(device)) return
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
                    Log.d(TAG, "AirPods disconnected")
                }
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                    if (!isAirPods(device)) return
                    val level = intent.getIntExtra(
                        "android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                    if (level >= 0) {
                        Log.d(TAG, "BT battery broadcast: $level%")
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

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        }
        registerReceiver(btReceiver, filter)
    }

    private fun resolveConnectedAirPods() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            @Suppress("MissingPermission")
            bm.adapter?.bondedDevices
                ?.filter { isAirPods(it) }
                ?.firstOrNull { isDeviceActuallyConnected(it) }
                ?.let { device ->
                    connectedDevice = device
                    val battery = readBatteryLevel(device)
                    state = state.copy(
                        isConnected   = true,
                        deviceAddress = device.address,
                        deviceName    = getDeviceName(device),
                        batteryLeft   = if (battery >= 0) battery else -1,
                        batteryRight  = if (battery >= 0) battery else -1
                    )
                    broadcastState()
                    Log.d(TAG, "AirPods already connected: ${device.address}")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error: ${e.message}")
        }
    }

    private fun isDeviceActuallyConnected(device: BluetoothDevice): Boolean {
        return try {
            @Suppress("MissingPermission")
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as Boolean
        } catch (e: Exception) { false }
    }

    private fun readBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            (method.invoke(device) as Int)
        } catch (e: Exception) { -1 }
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

    // ─── Gestures ─────────────────────────────────────────────────────────────

    private fun loadGesturePreferences() {
        val prefs = getSharedPreferences("gestures", MODE_PRIVATE)
        state = state.copy(
            doubleTapAction = GestureAction.values()[prefs.getInt("DOUBLE_TAP", GestureAction.PLAY_PAUSE.ordinal)]
        )
    }

    private fun saveGesturePreference(action: GestureAction) {
        getSharedPreferences("gestures", MODE_PRIVATE).edit()
            .putInt("DOUBLE_TAP", action.ordinal).apply()
        state = state.copy(doubleTapAction = action)
    }

    private fun dispatchGestureAction(action: GestureAction) {
        when (action) {
            GestureAction.PLAY_PAUSE  -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            GestureAction.NEXT_TRACK  -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            GestureAction.PREV_TRACK  -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            GestureAction.VOICE_ASSISTANT -> launchVoiceAssistant()
            GestureAction.VOLUME_UP   -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            GestureAction.VOLUME_DOWN -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            GestureAction.OFF         -> {}
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun launchVoiceAssistant() {
        try {
            startActivity(Intent("android.intent.action.ASSIST")
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (e: Exception) {
            Log.w(TAG, "Voice assistant error: ${e.message}")
        }
    }

    // ─── Find My Buds ─────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(10_000L).build()
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
                    state = state.copy(lastKnownLocation = loc,
                        lastSeenTimestamp = System.currentTimeMillis())
                }
            }
        }
    }

    private fun saveLastLocation() {
        try {
            fusedLocation.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    state = state.copy(lastKnownLocation = it,
                        lastSeenTimestamp = System.currentTimeMillis())
                    broadcastState()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun playFindMySound() {
        // Stop any existing playback
        findMyPlayer?.stop()
        findMyPlayer?.release()
        findMyPlayer = null

        // Save and max out media volume so AirPods play it loudly
        val prevVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume  = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

        // Use USAGE_MEDIA so audio routes through connected AirPods (not phone speaker)
        val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        try {
            findMyPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@AirPodsService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer error: ${e.message}")
        }

        // Stop after 30 s and restore volume
        handler.postDelayed({
            findMyPlayer?.stop()
            findMyPlayer?.release()
            findMyPlayer = null
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prevVolume, 0)
        }, 30_000L)

        // Also vibrate phone
        val vibrator = getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
        Log.d(TAG, "Find My playing through AirPods")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, AirPodsApp.CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AirPods Controller")
            .setContentText("Scanning for AirPods…")
            .setContentIntent(intent)
            .setOngoing(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, AirPodsApp.CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AirPods Controller")
            .setContentText(buildNotifText())
            .setContentIntent(intent)
            .setOngoing(true).setShowWhen(false)
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
                putExtra("connected",    state.isConnected)
                putExtra("device_name",  state.deviceName)
                putExtra("battery_left", state.batteryLeft)
                putExtra("battery_right",state.batteryRight)
                putExtra("battery_case", state.batteryCase)
                putExtra("in_ear_left",  state.leftInEar)
                putExtra("in_ear_right", state.rightInEar)
                putExtra("auto_disc",    state.autoDisconnectEnabled)
                putExtra("ear_detect",   state.earDetectionEnabled)
                putExtra("double_tap",   state.doubleTapAction.ordinal)
                putExtra("last_lat",     state.lastKnownLocation?.latitude ?: 0.0)
                putExtra("last_lng",     state.lastKnownLocation?.longitude ?: 0.0)
                putExtra("last_seen",    state.lastSeenTimestamp)
            }
        )
    }
}
