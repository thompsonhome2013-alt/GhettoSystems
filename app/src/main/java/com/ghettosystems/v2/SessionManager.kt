package com.ghettosystems.v2

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "gs2_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_TOKEN, value) }

    var userId: Int
        get() = prefs.getInt(KEY_USER_ID, 0)
        set(value) = prefs.edit { putInt(KEY_USER_ID, value) }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit { putString(KEY_USERNAME, value) }

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit { putString(KEY_EMAIL, value) }

    fun isLoggedIn(): Boolean {
        applyIdleTimeoutIfNeeded()
        return hasCredentials()
    }

    /** Record when the whole app goes to the background (home screen, another app, etc.). */
    fun onAppBackgrounded() {
        if (!hasCredentials()) return
        prefs.edit { putLong(KEY_BACKGROUNDED_AT, System.currentTimeMillis()) }
    }

    /**
     * Called when the app returns to the foreground.
     * @return true if the session was cleared due to idle timeout.
     */
    fun onAppForegrounded(): Boolean {
        val expired = applyIdleTimeoutIfNeeded()
        if (!expired) {
            prefs.edit { remove(KEY_BACKGROUNDED_AT) }
        }
        return expired
    }

    fun clear() = prefs.edit { clear() }

    private fun hasCredentials(): Boolean = !token.isNullOrBlank() && userId > 0

    /** @return true if credentials were cleared because idle timeout elapsed. */
    private fun applyIdleTimeoutIfNeeded(): Boolean {
        val backgroundedAt = prefs.getLong(KEY_BACKGROUNDED_AT, 0L)
        if (backgroundedAt <= 0L) return false
        if (System.currentTimeMillis() - backgroundedAt < IDLE_TIMEOUT_MS) return false
        if (!hasCredentials()) {
            prefs.edit { remove(KEY_BACKGROUNDED_AT) }
            return false
        }
        clear()
        return true
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_EMAIL = "email"
        private const val KEY_BACKGROUNDED_AT = "backgrounded_at"

        /** Require login again after the app has been in the background this long. */
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
