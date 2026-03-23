package com.airpods.controller.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.airpods.controller.*
import com.airpods.controller.databinding.ActivityMainBinding
import com.airpods.controller.service.AirPodsService
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentState = AirPodsState()

    // ─── Permission Launcher ──────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startAirPodsService()
        } else {
            Toast.makeText(this,
                "Bluetooth & Location permissions are required for full functionality",
                Toast.LENGTH_LONG).show()
            startAirPodsService() // Start anyway — some features will degrade gracefully
        }
    }

    // ─── State Receiver ───────────────────────────────────────────────────────

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AirPodsService.ACTION_STATE_UPDATE) {
                updateFromIntent(intent)
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        requestPermissionsAndStart()
        registerReceiver()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(stateReceiver, IntentFilter(AirPodsService.ACTION_STATE_UPDATE))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
    }

    private fun registerReceiver() {
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(stateReceiver, IntentFilter(AirPodsService.ACTION_STATE_UPDATE))
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            perms += listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAirPodsService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAirPodsService() {
        val intent = Intent(this, AirPodsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUI() {
        // ANC buttons
        binding.btnAncOff.setOnClickListener         { sendAncCommand(AncMode.OFF) }
        binding.btnAncNoise.setOnClickListener       { sendAncCommand(AncMode.NOISE_CANCEL) }
        binding.btnAncTransparency.setOnClickListener { sendAncCommand(AncMode.TRANSPARENCY) }
        binding.btnAncAdaptive.setOnClickListener    { sendAncCommand(AncMode.ADAPTIVE) }

        // Auto disconnect toggle
        binding.switchAutoDisconnect.setOnCheckedChangeListener { _, checked ->
            sendServiceAction(AirPodsService.ACTION_SET_AUTO_DISC) {
                putExtra(AirPodsService.EXTRA_ENABLED, checked)
            }
        }

        // Ear detection toggle
        binding.switchEarDetection.setOnCheckedChangeListener { _, checked ->
            sendServiceAction(AirPodsService.ACTION_SET_EAR_DETECT) {
                putExtra(AirPodsService.EXTRA_ENABLED, checked)
            }
        }

        // Manual disconnect
        binding.btnDisconnect.setOnClickListener {
            sendServiceAction(AirPodsService.ACTION_DISCONNECT)
        }

        // Find My Buds
        binding.btnFindLeft.setOnClickListener   { activateFindMy() }
        binding.btnFindRight.setOnClickListener  { activateFindMy() }
        binding.btnFindBoth.setOnClickListener   { activateFindMy() }
        binding.btnOpenMap.setOnClickListener    { openLastLocationInMaps() }

        // Gesture rows
        binding.rowLeftHold.setOnClickListener  { showGesturePicker("Left Press & Hold") }
        binding.rowRightHold.setOnClickListener { showGesturePicker("Right Press & Hold") }
        binding.rowLeftDouble.setOnClickListener  { showGesturePicker("Left Double Tap") }
        binding.rowRightDouble.setOnClickListener { showGesturePicker("Right Double Tap") }
    }

    // ─── State → UI ───────────────────────────────────────────────────────────

    private fun updateFromIntent(intent: Intent) {
        val connected    = intent.getBooleanExtra("connected", false)
        val battLeft     = intent.getIntExtra("battery_left", -1)
        val battRight    = intent.getIntExtra("battery_right", -1)
        val battCase     = intent.getIntExtra("battery_case", -1)
        val inEarLeft    = intent.getBooleanExtra("in_ear_left", false)
        val inEarRight   = intent.getBooleanExtra("in_ear_right", false)
        val ancOrdinal   = intent.getIntExtra("anc_mode", 0)
        val autoDisc     = intent.getBooleanExtra("auto_disc", true)
        val earDetect    = intent.getBooleanExtra("ear_detect", true)
        val lastLat      = intent.getDoubleExtra("last_lat", 0.0)
        val lastLng      = intent.getDoubleExtra("last_lng", 0.0)
        val lastSeen     = intent.getLongExtra("last_seen", 0L)

        // Connection
        binding.tvConnectionStatus.text = if (connected) "Connected" else "Disconnected"
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.green else R.color.red)
        )
        binding.viewConnDot.setBackgroundResource(
            if (connected) R.drawable.dot_green else R.drawable.dot_red
        )

        // Battery
        updateBattery(binding.tvBattLeft,   binding.progressBattLeft,   battLeft,  "L")
        updateBattery(binding.tvBattRight,  binding.progressBattRight,  battRight, "R")
        updateBattery(binding.tvBattCase,   binding.progressBattCase,   battCase,  "Case")

        // Ear detection indicators
        binding.tvLeftEar.text  = if (inEarLeft)  "In Ear" else "Out"
        binding.tvRightEar.text = if (inEarRight) "In Ear" else "Out"
        binding.tvLeftEar.setTextColor(ContextCompat.getColor(this,
            if (inEarLeft) R.color.green else R.color.red))
        binding.tvRightEar.setTextColor(ContextCompat.getColor(this,
            if (inEarRight) R.color.green else R.color.red))

        // ANC buttons highlight
        val ancMode = AncMode.values()[ancOrdinal]
        binding.btnAncOff.isSelected          = ancMode == AncMode.OFF
        binding.btnAncNoise.isSelected        = ancMode == AncMode.NOISE_CANCEL
        binding.btnAncTransparency.isSelected = ancMode == AncMode.TRANSPARENCY
        binding.btnAncAdaptive.isSelected     = ancMode == AncMode.ADAPTIVE

        // Toggles (avoid triggering listener)
        binding.switchAutoDisconnect.setOnCheckedChangeListener(null)
        binding.switchEarDetection.setOnCheckedChangeListener(null)
        binding.switchAutoDisconnect.isChecked = autoDisc
        binding.switchEarDetection.isChecked   = earDetect
        setupUI() // Re-attach listeners

        // Find My last location
        if (lastSeen > 0) {
            val fmt = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            binding.tvLastSeen.text = "Last seen: ${fmt.format(Date(lastSeen))}"
            binding.tvLastLocation.text = "%.5f, %.5f".format(lastLat, lastLng)
            binding.btnOpenMap.visibility = View.VISIBLE
            currentState = currentState.copy(
                lastSeenTimestamp = lastSeen
            )
        }
    }

    private fun updateBattery(tv: android.widget.TextView,
                               progress: android.widget.ProgressBar,
                               level: Int, label: String) {
        if (level < 0) {
            tv.text = "$label: --"
            progress.progress = 0
        } else {
            tv.text = "$label: $level%"
            progress.progress = level
            val color = when {
                level > 50 -> R.color.green
                level > 20 -> R.color.yellow
                else       -> R.color.red
            }
            tv.setTextColor(ContextCompat.getColor(this, color))
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    private fun sendAncCommand(mode: AncMode) {
        sendServiceAction(AirPodsService.ACTION_SET_ANC) {
            putExtra(AirPodsService.EXTRA_ANC_MODE, mode.ordinal)
        }
        Toast.makeText(this, "ANC: ${mode.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun activateFindMy() {
        sendServiceAction(AirPodsService.ACTION_FIND_MY_PLAY)
        Toast.makeText(this, "Playing sound on AirPods…", Toast.LENGTH_SHORT).show()
    }

    private fun openLastLocationInMaps() {
        val lat = currentState.lastKnownLocation?.latitude ?: return
        val lng = currentState.lastKnownLocation?.longitude ?: return
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(AirPods+Last+Seen)")
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        })
    }

    private fun showGesturePicker(gestureLabel: String) {
        val options = GestureAction.values().map { it.displayName }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(gestureLabel)
            .setItems(options) { _, which ->
                val action = GestureAction.values()[which]
                Toast.makeText(this, "$gestureLabel → ${action.displayName}", Toast.LENGTH_SHORT).show()
                // In a full app: persist this preference and send to service
            }
            .show()
    }

    private fun sendServiceAction(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, AirPodsService::class.java).apply {
            this.action = action
            extras?.invoke(this)
        }
        startService(intent)
    }
}
