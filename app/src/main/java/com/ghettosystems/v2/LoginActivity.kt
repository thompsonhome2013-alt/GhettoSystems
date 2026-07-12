package com.ghettosystems.v2

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class LoginActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var biometricLogin: BiometricLoginManager
    private lateinit var btnLogin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        biometricLogin = BiometricLoginManager(this)

        if (session.isLoggedIn()) {
            LoginHelper.goHome(this)
            return
        }

        setContentView(R.layout.activity_login)

        val etEmail = findViewById<TextInputEditText>(R.id.et_email)
        val etPassword = findViewById<TextInputEditText>(R.id.et_password)
        btnLogin = findViewById(R.id.btn_login)

        findViewById<android.widget.TextView>(R.id.tv_signup).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        findViewById<android.widget.TextView>(R.id.tv_forgot).setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                LoginHelper.showError(this, "Enter email and password")
                return@setOnClickListener
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                LoginHelper.showError(this, "Enter a valid email")
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "Logging in..."
            LoginHelper.login(
                activity = this,
                email = email,
                password = password,
                session = session,
                biometricLogin = biometricLogin,
                offerBiometricEnrollment = true,
                onFinished = {
                    btnLogin.isEnabled = true
                    btnLogin.text = getString(R.string.login)
                }
            )
        }
    }
}