package com.ghettosystems.v2

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

object AuthHelper {

    fun isAuthError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains("authenticated", ignoreCase = true)
            || message.contains("unauthorized", ignoreCase = true)
    }

    fun handleAuthFailure(activity: AppCompatActivity, session: SessionManager) {
        session.clear()
        activity.startActivity(
            Intent(activity, SplashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        activity.finish()
    }
}