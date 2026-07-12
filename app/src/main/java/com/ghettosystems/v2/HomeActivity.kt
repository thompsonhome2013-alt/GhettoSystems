package com.ghettosystems.v2

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

class HomeActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { loadDevices() }
    private var hasLoadedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)

        if (!session.isLoggedIn()) {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        adapter = DeviceAdapter { device ->
            startActivity(DeviceRegistry.controlIntent(this, device.devId, device.devSerial))
        }

        findViewById<RecyclerView>(R.id.recycler_devices).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = this@HomeActivity.adapter
        }

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(Intent(this, RegisterDeviceActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                MaterialAlertDialogBuilder(this@HomeActivity, R.style.Theme_GhettoSystems2_Dialog)
                    .setTitle("Logout?")
                    .setMessage("Return to login?")
                    .setPositiveButton("Yes") { _, _ ->
                        session.clear()
                        startActivity(Intent(this@HomeActivity, SplashActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun loadDevices() {
        val token = session.token ?: return
        val statusView = findViewById<TextView>(R.id.tv_status)
        if (!hasLoadedOnce) {
            statusView.visibility = View.VISIBLE
        }

        Gs2Api.listDevices(token) { result ->
            runOnUiThread {
                statusView.visibility = View.GONE
                result.onSuccess { devices ->
                    hasLoadedOnce = true
                    adapter.submit(devices)
                    findViewById<TextView>(R.id.tv_empty).visibility =
                        if (devices.isEmpty()) View.VISIBLE else View.GONE
                    val countLabel = if (devices.size == 1) "device" else "devices"
                    findViewById<TextView>(R.id.tv_refresh_hint).text =
                        getString(R.string.dashboard_refresh_hint) +
                            " · ${devices.size} $countLabel paired"
                    handler.removeCallbacks(refreshRunnable)
                    handler.postDelayed(refreshRunnable, 5_000)
                }.onFailure {
                    val message = it.message?.takeIf { msg -> msg.isNotBlank() } ?: "Could not load devices"
                    if (AuthHelper.isAuthError(message)) {
                        AuthHelper.handleAuthFailure(this@HomeActivity, session)
                        return@onFailure
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}