package com.ghettosystems.v2

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

object DeviceRegistry {

    data class TypeConfig(
        val label: String,
        val pageTitle: String,
        val firmware: String,
        val card: String,
        val activityClass: Class<out AppCompatActivity>,
        val hasGarageDoor: Boolean = false,
        val hasPower: Boolean = true,
        val hasPressure: Boolean = true,
        val role: String? = null,
        val isHub: Boolean = false,
    )

    private val fallbackTypes = mapOf(
        "GV1" to TypeConfig(
            label = "Garage",
            pageTitle = "Garage",
            firmware = "GaragePLC",
            card = DeviceAdapter.CARD_GARAGE,
            activityClass = DeviceConfigActivity::class.java,
            hasGarageDoor = true,
            hasPower = true,
            hasPressure = true,
            isHub = true,
        ),
    )

    private var remoteTypes: Map<String, TypeConfig>? = null

    fun applyRemoteTypes(types: JSONObject) {
        val map = mutableMapOf<String, TypeConfig>()
        types.keys().forEach { key ->
            val obj = types.getJSONObject(key)
            map[key.uppercase()] = typeConfigFromJson(key, obj)
        }
        remoteTypes = map
    }

    fun applyDeviceType(devId: String, type: JSONObject, role: String? = null) {
        val key = if (role.isNullOrBlank()) {
            devId.uppercase()
        } else {
            "${devId.uppercase()}|${role.lowercase()}"
        }
        val current = remoteTypes?.toMutableMap() ?: mutableMapOf()
        current[key] = typeConfigFromJson(devId, type).copy(role = role)
        // Prefer hub type for physical key when role is null
        if (role.isNullOrBlank()) {
            current[devId.uppercase()] = typeConfigFromJson(devId, type)
        }
        remoteTypes = current
    }

    fun configFor(devId: String, role: String? = null): TypeConfig {
        val base = devId.uppercase()
        // Hub UI always uses physical type when role is null
        if (role.isNullOrBlank()) {
            return remoteTypes?.get(base)
                ?: fallbackTypes[base]
                ?: TypeConfig(
                    label = devId,
                    pageTitle = devId,
                    firmware = "",
                    card = DeviceAdapter.CARD_GENERIC,
                    activityClass = DeviceConfigActivity::class.java,
                )
        }
        val key = "$base|${role.lowercase()}"
        return remoteTypes?.get(key)
            ?: remoteTypes?.get(base)
            ?: fallbackTypes[base]
            ?: TypeConfig(
                label = devId,
                pageTitle = devId,
                firmware = "",
                card = DeviceAdapter.CARD_GENERIC,
                activityClass = DeviceConfigActivity::class.java,
            )
    }

    fun refresh(token: String, callback: ((Result<Unit>) -> Unit)? = null) {
        Gs2Api.getDeviceTypes(token) { result ->
            result.onSuccess { applyRemoteTypes(it) }
            callback?.invoke(result.map { })
        }
    }

    fun deviceMeta(config: TypeConfig, devId: String, devSerial: String): String {
        return if (config.firmware.isNotBlank()) {
            "$devId · $devSerial · ${config.firmware}"
        } else {
            "$devId · $devSerial"
        }
    }

    fun configIntent(
        host: AppCompatActivity,
        devId: String,
        devSerial: String,
        /** Dashboard-facing name (display name or product label), e.g. "Garage". */
        deviceName: String? = null,
    ): Intent {
        val config = configFor(devId)
        return Intent(host, config.activityClass).apply {
            putExtra("dev_id", devId)
            putExtra("dev_serial", devSerial)
            if (!deviceName.isNullOrBlank()) {
                putExtra("device_name", deviceName)
            }
        }
    }

    private fun typeConfigFromJson(devId: String, obj: JSONObject): TypeConfig {
        return TypeConfig(
            label = obj.optString("label", devId),
            pageTitle = obj.optString("page_title", devId),
            firmware = obj.optString("firmware", ""),
            card = obj.optString("card", DeviceAdapter.CARD_GENERIC),
            activityClass = DeviceConfigActivity::class.java,
            hasGarageDoor = obj.optBoolean("has_garage_door", false),
            hasPower = obj.optBoolean("has_power", true),
            hasPressure = obj.optBoolean("has_pressure", true),
            role = obj.optString("role", "").takeIf { it.isNotBlank() },
            isHub = obj.optBoolean("is_hub", false),
        )
    }
}
