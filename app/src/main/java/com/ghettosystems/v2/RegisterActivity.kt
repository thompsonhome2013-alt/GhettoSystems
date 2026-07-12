package com.ghettosystems.v2

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class RegisterActivity : AppCompatActivity() {

    private val apiUrl = "https://www.thompsonized.org/api_register.php"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val etUsername = findViewById<TextInputEditText>(R.id.et_username)
        val etEmail = findViewById<TextInputEditText>(R.id.et_email)
        val etPassword = findViewById<TextInputEditText>(R.id.et_password)
        val etConfirm = findViewById<TextInputEditText>(R.id.et_confirm_password)
        val btnCreate = findViewById<MaterialButton>(R.id.btn_create_account)
        findViewById<TextView>(R.id.tv_login_link).setOnClickListener { finish() }

        btnCreate.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirm = etConfirm.text.toString().trim()

            if (username.isEmpty() || email.isEmpty() || password.isEmpty() || confirm.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (username.length < 3) {
                Toast.makeText(this, "Username must be at least 3 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Enter a valid email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password != confirm) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnCreate.isEnabled = false
            btnCreate.text = "Creating Account..."

            val body = FormBody.Builder()
                .add("username", username)
                .add("email", email)
                .add("password", password)
                .build()
            val request = Request.Builder().url(apiUrl).post(body).build()
            OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    runOnUiThread {
                        btnCreate.isEnabled = true
                        btnCreate.text = getString(R.string.create_account)
                        Toast.makeText(this@RegisterActivity, "Connection error", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val text = response.body?.string().orEmpty()
                    runOnUiThread {
                        btnCreate.isEnabled = true
                        btnCreate.text = getString(R.string.create_account)
                        try {
                            val json = JSONObject(text)
                            val message = json.optString("message", "Account created")
                            Toast.makeText(this@RegisterActivity, message, Toast.LENGTH_LONG).show()
                            if (json.optString("status") == "success") finish()
                        } catch (_: Exception) {
                            Toast.makeText(this@RegisterActivity, "Registration failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            })
        }
    }
}