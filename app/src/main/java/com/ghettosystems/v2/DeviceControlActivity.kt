package com.ghettosystems.v2

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceControlActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshStatus() }
    private val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    private lateinit var devId: String
    private lateinit var devSerial: String
    private var currentPower = "OFF"
    private var deviceOnline = false
    private var busy = false

    private lateinit var glowRing: View
    private lateinit var btnPower: FrameLayout
    private lateinit var tvPressureValue: TextView
    private lateinit var tvPressureUnit: TextView
    private lateinit var tvPowerStatus: TextView
    private lateinit var tvOnline: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var statusDot: View
    private lateinit var tvLastUpdate: TextView

    private var glowAnimator: ValueAnimator? = null
    private var glowScaleX: ObjectAnimator? = null
    private var glowScaleY: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        setContentView(R.layout.activity_device_control)

        devId = intent.getStringExtra("dev_id").orEmpty()
        devSerial = intent.getStringExtra("dev_serial").orEmpty()

        glowRing = findViewById(R.id.glow_ring)
        btnPower = findViewById(R.id.btn_power)
        tvPressureValue = findViewById(R.id.tv_pressure_value)
        tvPressureUnit = findViewById(R.id.tv_pressure_unit)
        tvPowerStatus = findViewById(R.id.tv_power_status)
        tvOnline = findViewById(R.id.tv_online)
        statusPill = findViewById(R.id.status_pill)
        statusDot = findViewById(R.id.status_dot)
        tvLastUpdate = findViewById(R.id.tv_last_update)

        val typeConfig = DeviceRegistry.configFor(devId)
        findViewById<TextView>(R.id.tv_page_title).text = typeConfig.pageTitle
        findViewById<TextView>(R.id.tv_device_meta).text = "$devId · $devSerial"
        findViewById<View>(R.id.power_button_container).setOnClickListener { togglePower() }

        updatePowerButton()
        refreshStatus()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        stopGlowPulse()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (currentPower == "ON") {
            startGlowPulse()
        }
    }

    private fun refreshStatus() {
        val token = session.token ?: return
        Gs2Api.getStatus(token, devId, devSerial) { result ->
            runOnUiThread {
                result.onSuccess { json ->
                    if (!json.optBoolean("ok")) {
                        applyOfflineStatus()
                        return@onSuccess
                    }
                    val data = json.optJSONObject("data") ?: run {
                        applyOfflineStatus()
                        return@onSuccess
                    }
                    deviceOnline = data.optBoolean("online")
                    currentPower = if (deviceOnline) {
                        data.optString("power", "OFF")
                    } else {
                        "OFF"
                    }
                    val pressure = if (data.isNull("pressure_psi")) null else data.getDouble("pressure_psi")

                    updateOnlineStatus()
                    updatePressure(pressure)
                    updatePowerButton()
                    tvLastUpdate.text = "Last update: ${timeFormat.format(Date())}"
                }.onFailure { error ->
                    if (AuthHelper.isAuthError(error.message)) {
                        AuthHelper.handleAuthFailure(this@DeviceControlActivity, session)
                    } else {
                        applyOfflineStatus()
                    }
                }
                scheduleNextRefresh()
            }
        }
    }

    private fun applyOfflineStatus() {
        deviceOnline = false
        currentPower = "OFF"
        updateOnlineStatus()
        updatePowerButton()
        setPowerButtonEnabled(false)
    }

    private fun scheduleNextRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 5_000)
    }

    private fun togglePower() {
        if (busy) return
        if (!deviceOnline) {
            Toast.makeText(this, R.string.device_offline_hint, Toast.LENGTH_SHORT).show()
            return
        }

        val turnOn = currentPower != "ON"
        busy = true
        setPowerButtonEnabled(false)

        val token = session.token ?: return
        Gs2Api.setPower(token, devId, devSerial, turnOn) { result ->
            runOnUiThread {
                busy = false
                setPowerButtonEnabled(deviceOnline)
                result.onSuccess {
                    refreshStatus()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Command failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updatePressure(pressure: Double?) {
        tvPressureValue.text = if (pressure != null) {
            String.format(Locale.US, "%.1f", pressure)
        } else {
            getString(R.string.pressure_unknown)
        }
    }

    private fun updateOnlineStatus() {
        if (deviceOnline) {
            statusPill.setBackgroundResource(R.drawable.status_pill_online)
            statusDot.setBackgroundResource(R.drawable.status_dot_online)
            tvOnline.text = getString(R.string.online)
            tvOnline.setTextColor(getColor(R.color.colorPrimary))
        } else {
            statusPill.setBackgroundResource(R.drawable.status_pill_offline)
            statusDot.setBackgroundResource(R.drawable.status_dot_offline)
            tvOnline.text = getString(R.string.offline)
            tvOnline.setTextColor(getColor(R.color.red_active))
        }
        setPowerButtonEnabled(deviceOnline && !busy)
    }

    private fun updatePowerButton() {
        val isOn = currentPower == "ON"
        btnPower.setBackgroundResource(if (isOn) R.drawable.power_button_on else R.drawable.power_button_off)

        if (isOn) {
            tvPressureValue.setTextColor(getColor(R.color.colorOnPrimary))
            tvPressureUnit.setTextColor(Color.parseColor("#D6E4FF"))
            tvPowerStatus.setTextColor(getColor(R.color.colorPrimary))
            tvPowerStatus.text = getString(R.string.compressor_on)
            glowRing.visibility = View.VISIBLE
            btnPower.elevation = 16f
            startGlowPulse()
        } else {
            tvPressureValue.setTextColor(getColor(R.color.colorOnBackground))
            tvPressureUnit.setTextColor(getColor(R.color.textSecondary))
            tvPowerStatus.setTextColor(getColor(R.color.textSecondary))
            tvPowerStatus.text = getString(R.string.compressor_off)
            glowRing.visibility = View.GONE
            btnPower.elevation = 8f
            stopGlowPulse()
        }
    }

    private fun setPowerButtonEnabled(enabled: Boolean) {
        findViewById<View>(R.id.power_button_container).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
        }
    }

    private fun startGlowPulse() {
        if (glowAnimator?.isRunning == true) return

        glowRing.alpha = 0.45f
        glowAnimator = ValueAnimator.ofFloat(0.35f, 0.85f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { glowRing.alpha = it.animatedValue as Float }
            start()
        }

        glowScaleX = ObjectAnimator.ofFloat(glowRing, View.SCALE_X, 1f, 1.08f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        glowScaleY = ObjectAnimator.ofFloat(glowRing, View.SCALE_Y, 1f, 1.08f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopGlowPulse() {
        glowAnimator?.cancel()
        glowAnimator = null
        glowScaleX?.cancel()
        glowScaleX = null
        glowScaleY?.cancel()
        glowScaleY = null
        glowRing.scaleX = 1f
        glowRing.scaleY = 1f
        glowRing.alpha = 1f
    }
}