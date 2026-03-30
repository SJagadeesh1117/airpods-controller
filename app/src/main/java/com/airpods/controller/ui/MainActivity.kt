package com.airpods.controller.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastLat = 0.0
    private var lastLng = 0.0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.values.all { it }) {
            Toast.makeText(this,
                "Bluetooth & Location permissions are required for full functionality",
                Toast.LENGTH_LONG).show()
        }
        startAirPodsService()
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AirPodsService.ACTION_STATE_UPDATE) updateFromIntent(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        loadGesturePreferences()
        requestPermissionsAndStart()
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

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms += listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAirPodsService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startAirPodsService() {
        val intent = Intent(this, AirPodsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun setupUI() {
        binding.switchAutoDisconnect.setOnCheckedChangeListener { _, checked ->
            sendServiceAction(AirPodsService.ACTION_SET_AUTO_DISC) {
                putExtra(AirPodsService.EXTRA_ENABLED, checked)
            }
        }
        binding.switchEarDetection.setOnCheckedChangeListener { _, checked ->
            sendServiceAction(AirPodsService.ACTION_SET_EAR_DETECT) {
                putExtra(AirPodsService.EXTRA_ENABLED, checked)
            }
        }
        binding.btnDisconnect.setOnClickListener { sendServiceAction(AirPodsService.ACTION_DISCONNECT) }
        binding.btnFindLeft.setOnClickListener   { activateFindMy() }
        binding.btnFindRight.setOnClickListener  { activateFindMy() }
        binding.btnFindBoth.setOnClickListener   { activateFindMy() }
        binding.btnOpenMap.setOnClickListener    { openLastLocationInMaps() }
        binding.rowLeftDouble.setOnClickListener  { showGesturePicker() }
        binding.rowRightDouble.setOnClickListener { showGesturePicker() }
    }

    private fun updateFromIntent(intent: Intent) {
        val connected  = intent.getBooleanExtra("connected", false)
        val battLeft   = intent.getIntExtra("battery_left", -1)
        val battRight  = intent.getIntExtra("battery_right", -1)
        val battCase   = intent.getIntExtra("battery_case", -1)
        val inEarLeft  = intent.getBooleanExtra("in_ear_left", false)
        val inEarRight = intent.getBooleanExtra("in_ear_right", false)
        val autoDisc   = intent.getBooleanExtra("auto_disc", true)
        val earDetect  = intent.getBooleanExtra("ear_detect", true)
        val tapOrdinal = intent.getIntExtra("double_tap", 0)
        val lat        = intent.getDoubleExtra("last_lat", 0.0)
        val lng        = intent.getDoubleExtra("last_lng", 0.0)
        val lastSeen   = intent.getLongExtra("last_seen", 0L)

        binding.tvConnectionStatus.text = if (connected) "Connected" else "Disconnected"
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.green else R.color.red))
        binding.viewConnDot.setBackgroundResource(
            if (connected) R.drawable.dot_green else R.drawable.dot_red)

        updateBattery(binding.tvBattLeft,  binding.progressBattLeft,  battLeft,  "L")
        updateBattery(binding.tvBattRight, binding.progressBattRight, battRight, "R")
        updateBattery(binding.tvBattCase,  binding.progressBattCase,  battCase,  "Case")

        binding.tvLeftEar.text  = if (inEarLeft)  "In Ear" else "Out"
        binding.tvRightEar.text = if (inEarRight) "In Ear" else "Out"
        binding.tvLeftEar.setTextColor(ContextCompat.getColor(this,
            if (inEarLeft) R.color.green else R.color.red))
        binding.tvRightEar.setTextColor(ContextCompat.getColor(this,
            if (inEarRight) R.color.green else R.color.red))

        binding.switchAutoDisconnect.setOnCheckedChangeListener(null)
        binding.switchEarDetection.setOnCheckedChangeListener(null)
        binding.switchAutoDisconnect.isChecked = autoDisc
        binding.switchEarDetection.isChecked   = earDetect
        setupUI()

        val tapLabel = "${GestureAction.values()[tapOrdinal].displayName} ›"
        binding.tvLeftDoubleAction.text  = tapLabel
        binding.tvRightDoubleAction.text = tapLabel

        if (lastSeen > 0) {
            lastLat = lat; lastLng = lng
            val fmt = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            binding.tvLastSeen.text = "Last seen: ${fmt.format(Date(lastSeen))}"
            binding.tvLastLocation.text = "%.5f, %.5f".format(lat, lng)
            binding.btnOpenMap.visibility = View.VISIBLE
        }
    }

    private fun updateBattery(tv: android.widget.TextView,
                               progress: android.widget.ProgressBar,
                               level: Int, label: String) {
        if (level < 0) {
            tv.text = "$label: --"; progress.progress = 0
        } else {
            tv.text = "$label: $level%"; progress.progress = level
            val color = when { level > 50 -> R.color.green; level > 20 -> R.color.yellow; else -> R.color.red }
            tv.setTextColor(ContextCompat.getColor(this, color))
        }
    }

    private fun activateFindMy() {
        sendServiceAction(AirPodsService.ACTION_FIND_MY_PLAY)
        Toast.makeText(this, "Playing sound through AirPods…", Toast.LENGTH_SHORT).show()
    }

    private fun openLastLocationInMaps() {
        if (lastLat == 0.0 && lastLng == 0.0) return
        val uri = Uri.parse("geo:$lastLat,$lastLng?q=$lastLat,$lastLng(AirPods+Last+Seen)")
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        })
    }

    private fun loadGesturePreferences() {
        val prefs = getSharedPreferences("gestures", MODE_PRIVATE)
        val action = GestureAction.values()[prefs.getInt("DOUBLE_TAP", GestureAction.PLAY_PAUSE.ordinal)]
        val label = "${action.displayName} ›"
        binding.tvLeftDoubleAction.text  = label
        binding.tvRightDoubleAction.text = label
    }

    private fun showGesturePicker() {
        val options = GestureAction.values().map { it.displayName }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Double Tap Action")
            .setItems(options) { _, which ->
                val action = GestureAction.values()[which]
                val label = "${action.displayName} ›"
                binding.tvLeftDoubleAction.text  = label
                binding.tvRightDoubleAction.text = label
                getSharedPreferences("gestures", MODE_PRIVATE).edit()
                    .putInt("DOUBLE_TAP", action.ordinal).apply()
                sendServiceAction(AirPodsService.ACTION_SET_GESTURE) {
                    putExtra(AirPodsService.EXTRA_GESTURE_ACTION, action.ordinal)
                }
            }.show()
    }

    private fun sendServiceAction(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, AirPodsService::class.java).apply {
            this.action = action; extras?.invoke(this)
        }
        startService(intent)
    }
}
