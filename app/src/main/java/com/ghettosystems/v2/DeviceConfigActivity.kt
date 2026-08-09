package com.ghettosystems.v2

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

class DeviceConfigActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshStatus() }

    private lateinit var devId: String
    private lateinit var devSerial: String
    private lateinit var typeConfig: DeviceRegistry.TypeConfig
    /** Same title as the dashboard card (e.g. Garage), never first endpoint (Air Compressor). */
    private var hubDisplayName: String = ""
    private var deviceOnline = false
    private val endpointChecks = mutableMapOf<String, CheckBox>()

    private lateinit var statusPill: LinearLayout
    private lateinit var statusDot: View
    private lateinit var tvOnline: TextView
    private lateinit var tvPageTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        setContentView(R.layout.activity_device_config)

        devId = intent.getStringExtra("dev_id").orEmpty()
        devSerial = intent.getStringExtra("dev_serial").orEmpty()
        typeConfig = DeviceRegistry.configFor(devId)
        hubDisplayName = intent.getStringExtra("device_name")
            ?.takeIf { it.isNotBlank() }
            ?: typeConfig.pageTitle.takeIf { it.isNotBlank() }
            ?: typeConfig.label

        statusPill = findViewById(R.id.status_pill)
        statusDot = findViewById(R.id.status_dot)
        tvOnline = findViewById(R.id.tv_online)
        tvPageTitle = findViewById(R.id.tv_page_title)

        findViewById<TextView>(R.id.tv_back_dashboard).setOnClickListener {
            finish()
        }

        tvPageTitle.text = hubDisplayName
        findViewById<TextView>(R.id.tv_device_meta).text =
            DeviceRegistry.deviceMeta(typeConfig, devId, devSerial)

        bindInfoRow(R.id.row_device_id, getString(R.string.device_id), devId)
        bindInfoRow(R.id.row_device_serial, getString(R.string.device_serial), devSerial)
        bindInfoRow(
            R.id.row_firmware,
            getString(R.string.device_firmware),
            typeConfig.firmware.ifBlank { "—" }
        )
        // Product / hub label (Garage), not a nested endpoint name
        bindInfoRow(
            R.id.row_product_name,
            getString(R.string.device_product_name),
            typeConfig.label.ifBlank { hubDisplayName },
        )

        findViewById<MaterialButton>(R.id.btn_save_endpoints).setOnClickListener {
            saveEndpoints()
        }

        findViewById<MaterialButton>(R.id.btn_unpair).setOnClickListener {
            confirmUnpair()
        }

        loadEndpoints()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun bindInfoRow(rowId: Int, label: String, value: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tv_info_label).text = label
        row.findViewById<TextView>(R.id.tv_info_value).text = value
        row.findViewById<View>(R.id.info_divider).visibility = View.GONE
    }

    private fun loadEndpoints() {
        val token = session.token ?: return
        Gs2Api.getDeviceSettings(token, devId, devSerial) { result ->
            runOnUiThread {
                result.onSuccess { data ->
                    val arr = data.optJSONArray("endpoints") ?: return@onSuccess
                    if (arr.length() == 0) return@onSuccess
                    val section = findViewById<LinearLayout>(R.id.section_endpoints)
                    val list = findViewById<LinearLayout>(R.id.endpoint_list)
                    section.visibility = View.VISIBLE
                    list.removeAllViews()
                    endpointChecks.clear()
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val role = ep.optString("role", "").takeIf { it.isNotBlank() } ?: continue
                        val label = ep.optString("label", role)
                        val enabled = ep.optBoolean("enabled", true)
                        val check = CheckBox(this).apply {
                            text = label
                            isChecked = enabled
                            setTextColor(getColor(R.color.colorOnBackground))
                            textSize = 16f
                        }
                        endpointChecks[role] = check
                        list.addView(check)
                    }
                }.onFailure {
                    // Non-fatal; hub prefs optional on older servers.
                }
            }
        }
    }

    private fun saveEndpoints() {
        val token = session.token ?: return
        if (endpointChecks.isEmpty()) return
        val map = endpointChecks.mapValues { it.value.isChecked }
        findViewById<MaterialButton>(R.id.btn_save_endpoints).isEnabled = false
        Gs2Api.setEndpointPrefs(token, devId, devSerial, map) { result ->
            runOnUiThread {
                findViewById<MaterialButton>(R.id.btn_save_endpoints).isEnabled = true
                result.onSuccess {
                    showFlash(getString(R.string.devices_saved), success = true)
                }.onFailure {
                    showFlash(it.message ?: "Could not save devices", success = false)
                }
            }
        }
    }

    private fun applySettingsFromStatus(data: JSONObject) {
        // Prefer hub / display name from server; never let endpoint labels replace the title.
        val productLabel = data.optString("product_label", "").takeIf { it.isNotBlank() }
        val displayName = data.optString("display_name", "").takeIf { it.isNotBlank() }
        val resolvedName = data.optString("name", "").takeIf { it.isNotBlank() }

        val title = when {
            !displayName.isNullOrBlank() -> displayName
            !productLabel.isNullOrBlank() -> productLabel
            !resolvedName.isNullOrBlank() &&
                !resolvedName.equals("Air Compressor", ignoreCase = true) -> resolvedName
            else -> hubDisplayName.ifBlank { typeConfig.pageTitle }
        }
        hubDisplayName = title
        tvPageTitle.text = title

        if (!productLabel.isNullOrBlank()) {
            bindInfoRow(
                R.id.row_product_name,
                getString(R.string.device_product_name),
                productLabel,
            )
        }
    }

    private fun refreshStatus() {
        val token = session.token ?: return
        Gs2Api.getStatus(token, devId, devSerial) { result ->
            runOnUiThread {
                result.onSuccess { json ->
                    if (!json.optBoolean("ok")) {
                        deviceOnline = false
                        updateOnlineStatus()
                        scheduleNextRefresh()
                        return@onSuccess
                    }
                    val data = json.optJSONObject("data") ?: run {
                        deviceOnline = false
                        updateOnlineStatus()
                        scheduleNextRefresh()
                        return@onSuccess
                    }
                    deviceOnline = data.optBoolean("online")
                    applySettingsFromStatus(data)
                    updateOnlineStatus()
                }.onFailure { error ->
                    if (AuthHelper.isAuthError(error.message)) {
                        AuthHelper.handleAuthFailure(this@DeviceConfigActivity, session)
                    } else {
                        deviceOnline = false
                        updateOnlineStatus()
                    }
                }
                scheduleNextRefresh()
            }
        }
    }

    private fun scheduleNextRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 2_000)
    }

    private fun updateOnlineStatus() {
        val useNeutralOffline = typeConfig.card == DeviceAdapter.CARD_GARAGE
        if (deviceOnline) {
            statusPill.setBackgroundResource(R.drawable.status_pill_online)
            statusDot.setBackgroundResource(R.drawable.status_dot_online)
            tvOnline.text = getString(R.string.online)
            tvOnline.setTextColor(getColor(R.color.colorPrimary))
        } else {
            statusPill.setBackgroundResource(
                if (useNeutralOffline) {
                    R.drawable.status_pill_offline_neutral
                } else {
                    R.drawable.status_pill_offline
                }
            )
            statusDot.setBackgroundResource(
                if (useNeutralOffline) {
                    R.drawable.status_dot_offline_neutral
                } else {
                    R.drawable.status_dot_offline
                }
            )
            tvOnline.text = getString(R.string.offline)
            tvOnline.setTextColor(
                getColor(if (useNeutralOffline) R.color.textSecondary else R.color.red_active)
            )
        }
    }

    private fun confirmUnpair() {
        val token = session.token ?: return
        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems2_Dialog)
            .setTitle(R.string.unpair_confirm_title)
            .setMessage(R.string.unpair_confirm_message)
            .setPositiveButton(R.string.unpair_device) { _, _ ->
                Gs2Api.unpairDevice(token, devId, devSerial) { result ->
                    runOnUiThread {
                        result.onSuccess { message ->
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                            startActivity(
                                Intent(this, HomeActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            )
                            finish()
                        }.onFailure {
                            val msg = it.message?.takeIf { m -> m.isNotBlank() } ?: "Could not unpair device"
                            if (AuthHelper.isAuthError(msg)) {
                                AuthHelper.handleAuthFailure(this@DeviceConfigActivity, session)
                                return@onFailure
                            }
                            showFlash(msg, success = false)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFlash(message: String, success: Boolean) {
        val flash = findViewById<TextView>(R.id.tv_flash)
        flash.text = message
        flash.visibility = View.VISIBLE
        if (success) {
            flash.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
            flash.setBackgroundColor(0x1F0052FF)
        } else {
            flash.setTextColor(ContextCompat.getColor(this, R.color.red_active))
            flash.setBackgroundColor(0x1FEF4444)
        }
    }
}
