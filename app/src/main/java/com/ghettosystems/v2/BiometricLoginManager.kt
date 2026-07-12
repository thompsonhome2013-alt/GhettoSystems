package com.ghettosystems.v2

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BiometricLoginManager(private val context: Context) {

    data class StoredCredentials(val email: String, val password: String)

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun canUseBiometric(): Boolean {
        val result = BiometricManager.from(context)
            .canAuthenticate(ALLOWED_AUTHENTICATORS)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun isEnabled(): Boolean {
        return encryptedPrefs.getBoolean(KEY_ENABLED, false) && getStoredCredentials() != null
    }

    fun saveCredentials(email: String, password: String) {
        encryptedPrefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getStoredCredentials(): StoredCredentials? {
        if (!encryptedPrefs.getBoolean(KEY_ENABLED, false)) return null
        val email = encryptedPrefs.getString(KEY_EMAIL, null)?.trim().orEmpty()
        val password = encryptedPrefs.getString(KEY_PASSWORD, null).orEmpty()
        if (email.isEmpty() || password.isEmpty()) return null
        return StoredCredentials(email, password)
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onUsePassword: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!canUseBiometric()) {
            onUsePassword()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onUsePassword()
                        else -> onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    onError(activity.getString(R.string.biometric_failed))
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(activity.getString(R.string.biometric_use_password))
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
    }

    companion object {
        private const val ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        private const val PREFS_FILE = "gs2_biometric_login"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
    }
}