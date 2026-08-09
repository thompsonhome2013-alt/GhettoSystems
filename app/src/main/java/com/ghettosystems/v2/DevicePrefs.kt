package com.ghettosystems.v2

import android.content.Context
import androidx.core.content.edit

object DevicePrefs {
    private const val PREFS_NAME = "gs2_device_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun displayNameKey(devId: String, devSerial: String): String =
        "display_name_${devId.uppercase()}_${devSerial.uppercase()}"

    fun displayName(context: Context, devId: String, devSerial: String): String? {
        return prefs(context).getString(displayNameKey(devId, devSerial), null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setDisplayName(context: Context, devId: String, devSerial: String, name: String?) {
        val key = displayNameKey(devId, devSerial)
        val trimmed = name?.trim().orEmpty()
        prefs(context).edit {
            if (trimmed.isEmpty()) {
                remove(key)
            } else {
                putString(key, trimmed)
            }
        }
    }
}