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

class ForgotPasswordActivity : AppCompatActivity() {

    private val apiUrl = "https://www.thompsonized.org/api_forgot_password.php"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        val etEmail = findViewById<TextInputEditText>(R.id.et_email)
        val btnSend = findViewById<MaterialButton>(R.id.btn_send_reset)
        findViewById<TextView>(R.id.tv_back_to_login).setOnClickListener { finish() }

        btnSend.setOnClickListener {
            val email = etEmail.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSend.isEnabled = false
            btnSend.text = "Sending..."

            val body = FormBody.Builder().add("email", email).build()
            val request = Request.Builder().url(apiUrl).post(body).build()
            OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    runOnUiThread {
                        btnSend.isEnabled = true
                        btnSend.text = getString(R.string.send_reset_link)
                        Toast.makeText(this@ForgotPasswordActivity, "Connection error", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val text = response.body?.string().orEmpty()
                    runOnUiThread {
                        btnSend.isEnabled = true
                        btnSend.text = getString(R.string.send_reset_link)
                        try {
                            val json = JSONObject(text)
                            Toast.makeText(
                                this@ForgotPasswordActivity,
                                json.optString("message", "Request sent"),
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (_: Exception) {
                            Toast.makeText(this@ForgotPasswordActivity, "Request failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            })
        }
    }
}