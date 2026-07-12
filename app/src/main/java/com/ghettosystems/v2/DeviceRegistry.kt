package com.ghettosystems.v2

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

object DeviceRegistry {

    data class TypeConfig(
        val label: String,
        val activityClass: Class<out AppCompatActivity>,
    )

    private val registry = mapOf(
        "GS2AIR" to TypeConfig(
            label = "UNO-AIR v3.2",
            activityClass = DeviceControlActivity::class.java,
        ),
    )

    fun configFor(devId: String): TypeConfig {
        return registry[devId.uppercase()] ?: TypeConfig(
            label = devId,
            activityClass = DeviceControlActivity::class.java,
        )
    }

    fun controlIntent(host: AppCompatActivity, devId: String, devSerial: String): Intent {
        val config = configFor(devId)
        return Intent(host, config.activityClass).apply {
            putExtra("dev_id", devId)
            putExtra("dev_serial", devSerial)
        }
    }
}