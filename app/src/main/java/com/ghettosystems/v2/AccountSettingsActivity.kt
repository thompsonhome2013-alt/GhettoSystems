package com.ghettosystems.v2

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Locale

class AccountSettingsActivity : AppCompatActivity() {

    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)

        if (!session.isLoggedIn()) {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_account_settings)

        findViewById<TextView>(R.id.tv_back_dashboard).setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        UserMenuHelper.bind(
            activity = this,
            session = session,
            menuButton = findViewById<ImageButton>(R.id.btn_menu),
            highlightAccount = true,
            onLogout = { confirmLogout() }
        )

        findViewById<MaterialButton>(R.id.btn_update_password).setOnClickListener {
            submitPasswordChange()
        }

        bindInfoRow(R.id.row_app_version, getString(R.string.app_version), BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        loadProfile()
        loadPairedDevices()
    }

    private fun loadProfile() {
        val token = session.token ?: return

        Gs2Api.getAccountProfile(token) { result ->
            runOnUiThread {
                result.onSuccess { profile ->
                    bindInfoRow(R.id.row_email, getString(R.string.email), profile.email)
                    bindInfoRow(R.id.row_username, getString(R.string.username), profile.username)
                    bindInfoRow(
                        R.id.row_member_since,
                        getString(R.string.member_since),
                        formatMemberSince(profile.createdAt)
                    )
                    bindInfoRow(
                        R.id.row_device_count,
                        getString(R.string.paired_devices),
                        profile.deviceCount.toString()
                    )
                    session.email = profile.email
                    session.username = profile.username
                }.onFailure {
                    val message = it.message?.takeIf { msg -> msg.isNotBlank() } ?: "Could not load account"
                    if (AuthHelper.isAuthError(message)) {
                        AuthHelper.handleAuthFailure(this, session)
                        return@onFailure
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindInfoRow(rowId: Int, label: String, value: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tv_info_label).text = label
        row.findViewById<TextView>(R.id.tv_info_value).text = value
        val divider = row.findViewById<View>(R.id.info_divider)
        divider.visibility = if (rowId == R.id.row_email) View.GONE else View.VISIBLE
    }

    private fun formatMemberSince(createdAt: String): String {
        val parsers = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        )
        val output = SimpleDateFormat("MMM d, yyyy", Locale.US)
        for (parser in parsers) {
            try {
                val date = parser.parse(createdAt)
                if (date != null) return output.format(date)
            } catch (_: Exception) {
            }
        }
        return createdAt
    }

    private fun loadPairedDevices() {
        val token = session.token ?: return
        val container = findViewById<LinearLayout>(R.id.paired_devices_container)
        val emptyLabel = findViewById<TextView>(R.id.tv_no_paired_devices)

        Gs2Api.listDevices(token) { result ->
            runOnUiThread {
                result.onSuccess { devices ->
                    container.removeAllViews()
                    if (devices.isEmpty()) {
                        emptyLabel.visibility = View.VISIBLE
                        return@onSuccess
                    }
                    emptyLabel.visibility = View.GONE
                    val inflater = LayoutInflater.from(this)
                    // List one row per physical pair (unpair removes all logical endpoints).
                    val physical = devices.distinctBy { "${it.devId}|${it.devSerial}" }
                    physical.forEach { device ->
                        val row = inflater.inflate(R.layout.item_paired_device_row, container, false)
                        val config = DeviceRegistry.configFor(device.devId)
                        val label = device.displayName?.takeIf { it.isNotBlank() }
                            ?: config.label
                        row.findViewById<TextView>(R.id.tv_device_label).text = label
                        row.findViewById<TextView>(R.id.tv_device_meta).text =
                            DeviceRegistry.deviceMeta(config, device.devId, device.devSerial)
                        row.findViewById<View>(R.id.btn_unpair).setOnClickListener {
                            confirmUnpair(device)
                        }
                        container.addView(row)
                    }
                }.onFailure {
                    val message = it.message?.takeIf { msg -> msg.isNotBlank() } ?: "Could not load devices"
                    if (AuthHelper.isAuthError(message)) {
                        AuthHelper.handleAuthFailure(this, session)
                        return@onFailure
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmUnpair(device: Gs2Api.Device) {
        val token = session.token ?: return
        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems2_Dialog)
            .setTitle(R.string.unpair_confirm_title)
            .setMessage(R.string.unpair_confirm_message)
            .setPositiveButton(R.string.unpair_device) { _, _ ->
                Gs2Api.unpairDevice(token, device.devId, device.devSerial) { result ->
                    runOnUiThread {
                        result.onSuccess { message ->
                            showFlash(message, success = true)
                            loadProfile()
                            loadPairedDevices()
                        }.onFailure {
                            val msg = it.message?.takeIf { m -> m.isNotBlank() } ?: "Could not unpair device"
                            if (AuthHelper.isAuthError(msg)) {
                                AuthHelper.handleAuthFailure(this, session)
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

    private fun submitPasswordChange() {
        val current = findViewById<TextInputEditText>(R.id.et_current_password).text?.toString().orEmpty()
        val newPass = findViewById<TextInputEditText>(R.id.et_new_password).text?.toString().orEmpty()
        val confirm = findViewById<TextInputEditText>(R.id.et_confirm_password).text?.toString().orEmpty()
        val token = session.token ?: return

        findViewById<MaterialButton>(R.id.btn_update_password).isEnabled = false

        Gs2Api.changePassword(token, current, newPass, confirm) { result ->
            runOnUiThread {
                findViewById<MaterialButton>(R.id.btn_update_password).isEnabled = true
                result.onSuccess { message ->
                    showFlash(message, success = true)
                    findViewById<TextInputEditText>(R.id.et_current_password).text?.clear()
                    findViewById<TextInputEditText>(R.id.et_new_password).text?.clear()
                    findViewById<TextInputEditText>(R.id.et_confirm_password).text?.clear()
                }.onFailure {
                    val msg = it.message?.takeIf { m -> m.isNotBlank() } ?: "Could not update password"
                    if (AuthHelper.isAuthError(msg)) {
                        AuthHelper.handleAuthFailure(this, session)
                        return@onFailure
                    }
                    showFlash(msg, success = false)
                }
            }
        }
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
}