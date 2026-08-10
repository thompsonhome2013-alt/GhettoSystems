package com.ghettosystems.v2

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

object LoginHelper {

    @SuppressLint("HardwareIds")
    fun login(
        activity: AppCompatActivity,
        email: String,
        password: String,
        session: SessionManager,
        biometricLogin: BiometricLoginManager,
        offerBiometricEnrollment: Boolean,
        onFinished: (() -> Unit)? = null
    ) {
        val uniqueId = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)
        Gs2Api.login(
            email, password, uniqueId,
            android.os.Build.MODEL, android.os.Build.VERSION.RELEASE
        ) { result ->
            activity.runOnUiThread {
                onFinished?.invoke()
                result.onSuccess { json ->
                    if (!json.optBoolean("ok")) {
                        val error = json.optString("error", "Login failed")
                        if (AuthHelper.isAuthError(error)) {
                            session.clear()
                        }
                        showError(activity, error)
                        return@onSuccess
                    }
                    saveSession(session, json.getJSONObject("data"), email)
                    DeviceRegistry.refresh(session.token.orEmpty())
                    if (biometricLogin.isEnabled()) {
                        biometricLogin.saveCredentials(email, password)
                        goHome(activity)
                    } else if (offerBiometricEnrollment && biometricLogin.canUseBiometric()) {
                        offerBiometricEnrollment(activity, email, password, biometricLogin)
                    } else {
                        goHome(activity)
                    }
                }.onFailure {
                    Toast.makeText(activity, "Connection failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveSession(session: SessionManager, data: JSONObject, email: String) {
        session.token = data.getString("token")
        session.userId = data.getInt("user_id")
        session.username = data.optString("username")
        session.email = email
    }

    private fun offerBiometricEnrollment(
        activity: AppCompatActivity,
        email: String,
        password: String,
        biometricLogin: BiometricLoginManager
    ) {
        MaterialAlertDialogBuilder(activity, R.style.Theme_GhettoSystems_Dialog)
            .setTitle(R.string.enable_biometric_title)
            .setMessage(R.string.enable_biometric_message)
            .setPositiveButton(R.string.enable_biometric_positive) { dialog, _ ->
                biometricLogin.saveCredentials(email, password)
                dialog.dismiss()
                goHome(activity)
            }
            .setNegativeButton(R.string.enable_biometric_negative) { dialog, _ ->
                dialog.dismiss()
                goHome(activity)
            }
            .setCancelable(false)
            .show()
    }

    fun goHome(activity: Context) {
        activity.startActivity(Intent(activity, HomeActivity::class.java))
        if (activity is AppCompatActivity) {
            activity.finish()
        }
    }

    fun showError(activity: AppCompatActivity, message: String) {
        MaterialAlertDialogBuilder(activity, R.style.Theme_GhettoSystems_Dialog)
            .setTitle("Login")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}