package com.ghettosystems.v2

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class RegisterDeviceActivity : AppCompatActivity() {

    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        setContentView(R.layout.activity_register_device)

        val etDevId = findViewById<TextInputEditText>(R.id.et_dev_id)
        val etSerial = findViewById<TextInputEditText>(R.id.et_serial)
        val etCode = findViewById<TextInputEditText>(R.id.et_pairing_code)
        val btnFind = findViewById<MaterialButton>(R.id.btn_find)
        val btnClaim = findViewById<MaterialButton>(R.id.btn_claim)

        btnFind.setOnClickListener {
            val token = session.token ?: return@setOnClickListener
            val devId = etDevId.text.toString().trim()
            val serial = etSerial.text.toString().trim()
            Gs2Api.searchDevice(token, devId, serial) { result ->
                runOnUiThread {
                    result.onSuccess { found ->
                        Toast.makeText(
                            this,
                            if (found) "Device found — enter pairing code" else "Device not found",
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure {
                        Toast.makeText(this, "Search failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnClaim.setOnClickListener {
            val token = session.token ?: return@setOnClickListener
            val devId = etDevId.text.toString().trim()
            val serial = etSerial.text.toString().trim()
            val code = etCode.text.toString().trim()
            Gs2Api.claimDevice(token, devId, serial, code) { result ->
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this, "Device paired", Toast.LENGTH_SHORT).show()
                        finish()
                    }.onFailure {
                        Toast.makeText(this, it.message ?: "Claim failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}