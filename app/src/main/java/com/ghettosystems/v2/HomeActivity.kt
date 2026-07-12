package com.ghettosystems.v2

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)

        if (!session.isLoggedIn()) {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        val displayName = session.username?.takeIf { it.isNotBlank() } ?: getString(R.string.email)
        findViewById<TextView>(R.id.tv_user_email).text = displayName
        findViewById<TextView>(R.id.tv_welcome).text =
            getString(R.string.welcome_back_user, displayName)

        findViewById<LinearLayout>(R.id.btn_user_menu).setOnClickListener { view ->
            PopupMenu(this, view).apply {
                menu.add(getString(R.string.logout))
                setOnMenuItemClickListener {
                    confirmLogout()
                    true
                }
                show()
            }
        }

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
                confirmLogout()
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

    private fun loadDevices() {
        val token = session.token ?: return

        Gs2Api.listDevices(token) { result ->
            runOnUiThread {
                result.onSuccess { devices ->
                    adapter.submit(devices)
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