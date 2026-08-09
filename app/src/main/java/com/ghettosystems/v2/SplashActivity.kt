package com.ghettosystems.v2

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var biometricLogin: BiometricLoginManager
    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private val goToLoginRunnable = Runnable { goToLogin() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        biometricLogin = BiometricLoginManager(this)

        setContentView(R.layout.activity_splash)

        when {
            biometricLogin.isEnabled() && biometricLogin.canUseBiometric() -> {
                window.decorView.post { startBiometricLogin() }
            }
            session.isLoggedIn() -> {
                resumeExistingSession()
            }
            else -> {
                scheduleLoginAfterSplash()
            }
        }
    }

    private fun startBiometricLogin() {
        biometricLogin.authenticate(
            activity = this,
            onSuccess = {
                val creds = biometricLogin.getStoredCredentials()
                if (creds == null) {
                    session.clear()
                    scheduleLoginAfterSplash()
                    return@authenticate
                }
                LoginHelper.login(
                    activity = this,
                    email = creds.email,
                    password = creds.password,
                    session = session,
                    biometricLogin = biometricLogin,
                    offerBiometricEnrollment = false
                )
            },
            onUsePassword = {
                session.clear()
                scheduleLoginAfterSplash()
            },
            onError = { message ->
                if (message.isNotBlank()) {
                    LoginHelper.showError(this, message)
                }
            }
        )
    }

    private fun resumeExistingSession() {
        val token = session.token
        if (token.isNullOrBlank()) {
            session.clear()
            scheduleLoginAfterSplash()
            return
        }

        Gs2Api.listDevices(token) { result ->
            runOnUiThread {
                if (result.isSuccess) {
                    DeviceRegistry.refresh(token)
                    LoginHelper.goHome(this)
                } else if (AuthHelper.isAuthError(result.exceptionOrNull()?.message)) {
                    session.clear()
                    scheduleLoginAfterSplash()
                } else {
                    LoginHelper.goHome(this)
                }
            }
        }
    }

    private fun scheduleLoginAfterSplash() {
        if (navigated || isFinishing) return
        handler.removeCallbacks(goToLoginRunnable)
        handler.postDelayed(goToLoginRunnable, SPLASH_DELAY_MS)
    }

    private fun goToLogin() {
        if (navigated || isFinishing) return
        navigated = true
        handler.removeCallbacks(goToLoginRunnable)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToLoginRunnable)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_DELAY_MS = 8_000L
    }
}