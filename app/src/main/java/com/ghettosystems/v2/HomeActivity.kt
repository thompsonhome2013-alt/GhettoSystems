package com.ghettosystems.v2

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { loadDevices() }
    private val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    /** Drop stale listDevices responses when a newer load is already in flight. */
    private var devicesLoadGeneration = 0

    /**
     * After a power toggle, prefer the commanded state for a short window so a
     * concurrent poll (or lagging device heartbeat) cannot flip the UI back.
     */
    private val powerOverrideUntil = mutableMapOf<String, Pair<String, Long>>()

    private val doorCooldownMap = mutableMapOf<String, Int>()
    private val doorBusyKeys = mutableSetOf<String>()
    private val doorStatusMap = mutableMapOf<String, String>()

    private val doorCooldownRunnable = object : Runnable {
        override fun run() {
            var anyActive = false
            doorCooldownMap.keys.toList().forEach { key ->
                val remaining = doorCooldownMap[key] ?: 0
                if (remaining > 0) {
                    doorCooldownMap[key] = remaining - 1
                    if ((doorCooldownMap[key] ?: 0) > 0) {
                        anyActive = true
                    } else if (!doorBusyKeys.contains(key)) {
                        doorStatusMap.remove(key)
                    }
                    notifyDoorCard(key)
                }
            }
            if (anyActive) {
                handler.postDelayed(this, 1_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)

        if (!session.isLoggedIn()) {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        UserMenuHelper.bind(
            activity = this,
            session = session,
            menuButton = findViewById(R.id.btn_menu),
            onLogout = { confirmLogout() }
        )

        adapter = DeviceAdapter(
            onSettingsClick = { device ->
                val title = device.displayName?.takeIf { it.isNotBlank() } ?: device.name
                startActivity(
                    DeviceRegistry.configIntent(this, device.devId, device.devSerial, title)
                )
            },
            onPowerToggle = { device ->
                toggleDevicePower(device)
            },
            onPressureClick = { device ->
                showCutOutDialog(device)
            },
            onGarageDoorClick = { device ->
                confirmGarageDoorPulse(device)
            },
            doorStateFor = { device ->
                val key = DeviceAdapter.deviceKey(device)
                DoorCardState(
                    cooldownRemaining = doorCooldownMap[key] ?: 0,
                    busy = doorBusyKeys.contains(key),
                    statusMessage = doorStatusMap[key],
                )
            },
        )

        findViewById<RecyclerView>(R.id.recycler_devices).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = this@HomeActivity.adapter
        }

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(Intent(this, RegisterDeviceActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Leave app to the system launcher; do not prompt logout.
                moveTaskToBack(true)
            }
        })

        session.token?.let { DeviceRegistry.refresh(it) }
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(doorCooldownRunnable)
        super.onPause()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems2_Dialog)
            .setTitle("Logout?")
            .setMessage("Return to login?")
            .setPositiveButton("Yes") { _, _ ->
                session.clear()
                startActivity(
                    Intent(this, SplashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showCutOutDialog(device: Gs2Api.Device) {
        if (!device.online) {
            Toast.makeText(this, R.string.power_toggle_offline, Toast.LENGTH_SHORT).show()
            return
        }
        val token = session.token ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_set_cutout, null, false)
        val tvCutOut = view.findViewById<TextView>(R.id.tv_cutout_value)
        val tvCutIn = view.findViewById<TextView>(R.id.tv_cutin_value)
        val slider = view.findViewById<Slider>(R.id.slider_cutout)

        fun snap(v: Float): Int {
            val raw = v.toInt().coerceIn(40, 90)
            return (raw / 5) * 5
        }

        var selected = snap(device.cutOutPsi.toFloat())
        fun refreshLabels(psi: Int) {
            tvCutOut.text = getString(R.string.set_cutout_value, psi)
            tvCutIn.text = getString(R.string.set_cutin_value, psi - 20)
        }
        slider.valueFrom = 40f
        slider.valueTo = 90f
        slider.stepSize = 5f
        slider.value = selected.toFloat()
        refreshLabels(selected)
        slider.addOnChangeListener { _, value, _ ->
            selected = snap(value)
            refreshLabels(selected)
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems2_Dialog)
            .setTitle(R.string.set_cutout_title)
            .setView(view)
            .setPositiveButton(R.string.set_cutout_save) { _, _ ->
                val cut = selected
                Gs2Api.setCutOut(
                    token,
                    device.devId,
                    device.devSerial,
                    cut,
                    device.powerRole(),
                ) { result ->
                    runOnUiThread {
                        result.onSuccess { saved ->
                            Toast.makeText(
                                this,
                                getString(R.string.set_cutout_saved, saved, saved - 20),
                                Toast.LENGTH_SHORT,
                            ).show()
                            scheduleFastRefresh()
                            loadDevices()
                        }.onFailure {
                            val message = it.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Could not set shut-off pressure"
                            if (AuthHelper.isAuthError(message)) {
                                AuthHelper.handleAuthFailure(this@HomeActivity, session)
                                return@onFailure
                            }
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleDevicePower(device: Gs2Api.Device) {
        val token = session.token ?: return
        if (!device.online) {
            Toast.makeText(this, R.string.power_toggle_offline, Toast.LENGTH_SHORT).show()
            return
        }

        val turnOn = device.power.uppercase(Locale.US) != "ON"
        val commanded = if (turnOn) "ON" else "OFF"
        val key = DeviceAdapter.deviceKey(device)
        val previous = device.power.uppercase(Locale.US)

        // Optimistic UI: flip immediately so OFF/ON feels instant; revert on failure.
        powerOverrideUntil[key] = commanded to (System.currentTimeMillis() + POWER_OVERRIDE_MS)
        adapter.applyPowerState(device, commanded)
        adapter.setPowerBusy(device, true)
        scheduleFastRefresh()

        Gs2Api.setPower(token, device.devId, device.devSerial, turnOn, device.powerRole()) { result ->
            runOnUiThread {
                adapter.setPowerBusy(device, false)
                result.onSuccess { power ->
                    val next = power.ifBlank { commanded }.uppercase(Locale.US)
                    powerOverrideUntil[key] = next to (System.currentTimeMillis() + POWER_OVERRIDE_MS)
                    adapter.applyPowerState(device, next)
                    scheduleFastRefresh()
                    loadDevices()
                }.onFailure {
                    powerOverrideUntil.remove(key)
                    adapter.applyPowerState(device, previous)
                    val message = it.message?.takeIf { msg -> msg.isNotBlank() } ?: "Power command failed"
                    if (AuthHelper.isAuthError(message)) {
                        AuthHelper.handleAuthFailure(this@HomeActivity, session)
                        return@onFailure
                    }
                    Toast.makeText(this@HomeActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** After a power change, poll faster for a few cycles so PSI/power catch up quickly. */
    private var fastRefreshUntil = 0L

    private fun scheduleFastRefresh() {
        fastRefreshUntil = System.currentTimeMillis() + FAST_REFRESH_WINDOW_MS
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, FAST_REFRESH_MS)
    }

    private fun nextRefreshDelayMs(): Long {
        return if (System.currentTimeMillis() < fastRefreshUntil) FAST_REFRESH_MS else DEVICES_REFRESH_MS
    }

    private fun confirmGarageDoorPulse(device: Gs2Api.Device) {
        val key = DeviceAdapter.deviceKey(device)
        if (doorBusyKeys.contains(key) || (doorCooldownMap[key] ?: 0) > 0 || !device.online) {
            return
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems2_Dialog)
            .setTitle(R.string.garage_door_confirm_title)
            .setMessage(R.string.garage_door_confirm_message)
            .setPositiveButton(R.string.open_garage_door) { _, _ -> pulseGarageDoor(device) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pulseGarageDoor(device: Gs2Api.Device) {
        val token = session.token ?: return
        val key = DeviceAdapter.deviceKey(device)
        if (doorBusyKeys.contains(key) || (doorCooldownMap[key] ?: 0) > 0 || !device.online) {
            return
        }

        doorBusyKeys.add(key)
        doorStatusMap[key] = getString(R.string.garage_door_sent)
        adapter.notifyDeviceChanged(device)

        Gs2Api.pulseGarageDoor(token, device.devId, device.devSerial, device.doorRole()) { result ->
            runOnUiThread {
                doorBusyKeys.remove(key)
                result.onSuccess { cooldownSeconds ->
                    startDoorCooldown(key, cooldownSeconds)
                    doorStatusMap[key] = getString(R.string.garage_door_sent) +
                        " · ${timeFormat.format(Date())}"
                }.onFailure { error ->
                    if (error is DoorCooldownException) {
                        startDoorCooldown(key, error.retryAfterSeconds)
                        doorStatusMap.remove(key)
                    } else {
                        doorStatusMap.remove(key)
                    }
                    Toast.makeText(
                        this,
                        error.message ?: "Door command failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                adapter.notifyDeviceChanged(device)
            }
        }
    }

    private fun startDoorCooldown(key: String, seconds: Int) {
        val next = seconds.coerceAtLeast(0)
        val current = doorCooldownMap[key] ?: 0
        if (next > current) {
            doorCooldownMap[key] = next
            handler.removeCallbacks(doorCooldownRunnable)
            handler.postDelayed(doorCooldownRunnable, 1_000)
            notifyDoorCard(key)
        }
    }

    private fun syncDoorCooldownFromDevices(devices: List<Gs2Api.Device>) {
        devices.forEach { device ->
            if (!device.showDoor() && !DeviceRegistry.configFor(device.devId).hasGarageDoor) return@forEach
            val key = DeviceAdapter.deviceKey(device)
            if (device.doorCooldownRemaining > (doorCooldownMap[key] ?: 0)) {
                startDoorCooldown(key, device.doorCooldownRemaining)
            }
        }
    }

    private fun notifyDoorCard(key: String) {
        adapter.notifyDeviceKey(key)
    }

    private fun migrateLocalDisplayNames(devices: List<Gs2Api.Device>) {
        val token = session.token ?: return
        devices.forEach { device ->
            val local = DevicePrefs.displayName(this, device.devId, device.devSerial) ?: return@forEach
            if (!device.displayName.isNullOrBlank()) {
                DevicePrefs.setDisplayName(this, device.devId, device.devSerial, null)
                return@forEach
            }
            Gs2Api.setDisplayName(token, device.devId, device.devSerial, local) { result ->
                result.onSuccess {
                    DevicePrefs.setDisplayName(this@HomeActivity, device.devId, device.devSerial, null)
                    runOnUiThread { loadDevices() }
                }
            }
        }
    }

    private fun applyPowerOverrides(devices: List<Gs2Api.Device>): List<Gs2Api.Device> {
        if (powerOverrideUntil.isEmpty()) return devices
        val now = System.currentTimeMillis()
        return devices.map { device ->
            val key = DeviceAdapter.deviceKey(device)
            val override = powerOverrideUntil[key] ?: return@map device
            val (commanded, until) = override
            if (now >= until) {
                powerOverrideUntil.remove(key)
                return@map device
            }
            val reported = device.power.uppercase(Locale.US)
            if (reported == commanded) {
                // Device/server caught up — drop override.
                powerOverrideUntil.remove(key)
                return@map device
            }
            device.copy(power = commanded)
        }
    }

    private fun loadDevices() {
        val token = session.token ?: return
        val generation = ++devicesLoadGeneration

        Gs2Api.listDevices(token) { result ->
            runOnUiThread {
                if (generation != devicesLoadGeneration) return@runOnUiThread
                result.onSuccess { devices ->
                    migrateLocalDisplayNames(devices)
                    syncDoorCooldownFromDevices(devices)
                    adapter.submit(applyPowerOverrides(devices))
                    val isEmpty = devices.isEmpty()
                    findViewById<View>(R.id.empty_panel).visibility =
                        if (isEmpty) View.VISIBLE else View.GONE
                    findViewById<RecyclerView>(R.id.recycler_devices).visibility =
                        if (isEmpty) View.GONE else View.VISIBLE
                    val countLabel = if (devices.size == 1) "device" else "devices"
                    findViewById<TextView>(R.id.tv_refresh_hint).text =
                        getString(R.string.dashboard_refresh_hint) +
                            " · ${devices.size} $countLabel paired"
                    handler.removeCallbacks(refreshRunnable)
                    handler.postDelayed(refreshRunnable, nextRefreshDelayMs())
                }.onFailure {
                    val message = it.message?.takeIf { msg -> msg.isNotBlank() } ?: "Could not load devices"
                    if (AuthHelper.isAuthError(message)) {
                        AuthHelper.handleAuthFailure(this@HomeActivity, session)
                        return@onFailure
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    handler.removeCallbacks(refreshRunnable)
                    handler.postDelayed(refreshRunnable, nextRefreshDelayMs())
                }
            }
        }
    }

    companion object {
        /** Keep optimistic power until server/device agree (shorter than old 45s grace). */
        private const val POWER_OVERRIDE_MS = 12_000L
        /** Steady dashboard poll — matches ~1s device PSI heartbeats. */
        private const val DEVICES_REFRESH_MS = 1_000L
        /** After local power toggle, poll faster so status settles quickly. */
        private const val FAST_REFRESH_MS = 500L
        private const val FAST_REFRESH_WINDOW_MS = 8_000L
    }
}