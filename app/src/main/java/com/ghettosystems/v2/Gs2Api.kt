package com.ghettosystems.v2

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object Gs2Api {
    const val BASE = "https://www.thompsonized.org/api/v2/api"
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class Device(
        val devId: String,
        val devSerial: String,
        val name: String,
        val online: Boolean,
        val power: String,
        val pressurePsi: Double?
    )

    fun login(
        email: String,
        password: String,
        uniqueId: String,
        model: String,
        androidVersion: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        val body = FormBody.Builder()
            .add("email", email)
            .add("password", password)
            .add("unique_id", uniqueId)
            .add("device_name", "Ghetto Systems 2")
            .add("model", model)
            .add("android_version", androidVersion)
            .build()

        val request = Request.Builder().url("$BASE/login.php").post(body).build()
        client.newCall(request).enqueue(jsonCallback(callback))
    }

    fun listDevices(token: String, callback: (Result<List<Device>>) -> Unit) {
        val request = Request.Builder()
            .url("$BASE/devices_list.php")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Request failed"))))
                    return
                }
                val devices = mutableListOf<Device>()
                val arr = json.optJSONObject("data")?.optJSONArray("devices") ?: return callback(Result.success(devices))
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    devices.add(
                        Device(
                            devId = d.getString("dev_id"),
                            devSerial = d.getString("dev_serial"),
                            name = d.optString("name", "Air Compressor"),
                            online = d.optBoolean("online"),
                            power = d.optString("power", "OFF"),
                            pressurePsi = if (d.isNull("pressure_psi")) null else d.getDouble("pressure_psi")
                        )
                    )
                }
                callback(Result.success(devices))
            }
        })
    }

    fun searchDevice(token: String, devId: String, devSerial: String, callback: (Result<Boolean>) -> Unit) {
        val request = Request.Builder()
            .url("$BASE/devices_search.php?dev_id=$devId&dev_serial=$devSerial")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                callback(Result.success(json.optBoolean("ok")))
            }
        })
    }

    fun claimDevice(
        token: String,
        devId: String,
        devSerial: String,
        pairingCode: String,
        callback: (Result<Unit>) -> Unit
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .put("pairing_code", pairingCode)
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_claim.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (json.optBoolean("ok")) callback(Result.success(Unit))
                else callback(Result.failure(Exception(json.optString("error", "Claim failed"))))
            }
        })
    }

    fun setPower(
        token: String,
        devId: String,
        devSerial: String,
        on: Boolean,
        callback: (Result<String>) -> Unit
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .put("action", if (on) "ON" else "OFF")
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_control.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Control failed"))))
                    return
                }
                callback(Result.success(json.optJSONObject("data")?.optString("power", "OFF") ?: "OFF"))
            }
        })
    }

    fun getStatus(
        token: String,
        devId: String,
        devSerial: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_control.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        client.newCall(request).enqueue(jsonCallback(callback))
    }

    private fun jsonCallback(callback: (Result<JSONObject>) -> Unit) = object : Callback {
        override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
        override fun onResponse(call: Call, response: Response) {
            callback(Result.success(parseJson(response)))
        }
    }

    private fun parseJson(response: Response): JSONObject {
        val body = response.body?.string().orEmpty()
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "Invalid response")
        }
    }
}