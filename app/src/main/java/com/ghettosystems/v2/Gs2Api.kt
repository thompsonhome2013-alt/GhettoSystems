package com.ghettosystems.v2

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Gs2Api {
    const val BASE = "https://www.thompsonized.org/api/v2/api"
    private val client = OkHttpClient()
    /** Longer timeouts for multi-step agent + Evo-X LLM. */
    private val llmClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(320, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class LlmChatResult(
        val reply: String,
        val model: String?,
        val toolsUsed: List<String>,
        val threadId: String?,
        val latencyMs: Int?,
        val needsConfirm: Boolean,
        val agentRounds: Int?,
    )

    data class LlmMessage(
        val role: String,
        val content: String,
        val createdAt: String? = null,
    )

    data class McpServer(
        val id: Int,
        val name: String,
        val slug: String,
        val transport: String,
        val url: String,
        val command: String,
        val enabled: Boolean,
        val requireConfirm: Boolean,
        val timeoutSec: Int,
        val toolsCount: Int,
        val lastError: String?,
        val hasAuthToken: Boolean,
        val tools: List<McpTool> = emptyList(),
    )

    data class McpTool(
        val name: String,
        val description: String,
    )

    data class Endpoint(
        val role: String,
        val name: String,
        val enabled: Boolean,
        val hasPower: Boolean = false,
        val hasPressure: Boolean = false,
        val hasGarageDoor: Boolean = false,
        val power: String = "OFF",
        val pressurePsi: Double? = null,
        val doorCooldownRemaining: Int = 0,
    )

    data class Device(
        val devId: String,
        val devSerial: String,
        val name: String,
        val displayName: String?,
        val online: Boolean,
        val power: String,
        val pressurePsi: Double?,
        /** Compressor shut-off PSI (cut-in is always cut-out − 20). */
        val cutOutPsi: Int = 60,
        val doorCooldownRemaining: Int = 0,
        /** Logical endpoint role when flattened; null for hub cards. */
        val role: String? = null,
        val endpoints: List<Endpoint> = emptyList(),
    ) {
        val isHub: Boolean get() = endpoints.isNotEmpty()

        fun showCompressor(): Boolean =
            if (endpoints.isEmpty()) true
            else endpoints.any { it.enabled && it.hasPower }

        fun showDoor(): Boolean =
            if (endpoints.isEmpty()) false
            else endpoints.any { it.enabled && it.hasGarageDoor }

        fun powerRole(): String? =
            endpoints.firstOrNull { it.enabled && it.hasPower }?.role
                ?: role
                ?: "compressor".takeIf { showCompressor() }

        fun doorRole(): String? =
            endpoints.firstOrNull { it.enabled && it.hasGarageDoor }?.role
                ?: role
                ?: "door".takeIf { showDoor() }
    }

    data class AccountProfile(
        val email: String,
        val username: String,
        val createdAt: String,
        val deviceCount: Int
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
            .add("device_name", "Ghetto Systems")
            .add("model", model)
            .add("android_version", androidVersion)
            .build()

        val request = Request.Builder().url("$BASE/login.php").post(body).build()
        client.newCall(request).enqueue(jsonCallback(callback))
    }

    fun getDeviceTypes(token: String, callback: (Result<JSONObject>) -> Unit) {
        val request = Request.Builder()
            .url("$BASE/devices_types.php")
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
                callback(Result.success(json.optJSONObject("data")?.optJSONObject("types") ?: JSONObject()))
            }
        })
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
                val arr = json.optJSONObject("data")?.optJSONArray("devices")
                    ?: return callback(Result.success(devices))
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    val devId = d.getString("dev_id")
                    val role = d.optString("role", "").takeIf { it.isNotBlank() }
                    d.optJSONObject("type")?.let { typeJson ->
                        DeviceRegistry.applyDeviceType(devId, typeJson, role)
                    }
                    val endpoints = parseEndpoints(d.optJSONArray("endpoints"))
                    devices.add(
                        Device(
                            devId = devId,
                            devSerial = d.getString("dev_serial"),
                            name = d.optString("name", DeviceRegistry.configFor(devId, role).label),
                            displayName = if (d.isNull("display_name")) {
                                null
                            } else {
                                d.optString("display_name").takeIf { it.isNotBlank() }
                            },
                            online = d.optBoolean("online"),
                            power = d.optString("power", "OFF"),
                            pressurePsi = if (d.isNull("pressure_psi")) null else d.getDouble("pressure_psi"),
                            cutOutPsi = d.optInt("cut_out_psi", 60).coerceIn(40, 90),
                            doorCooldownRemaining = d.optInt("door_cooldown_remaining", 0),
                            role = role,
                            endpoints = endpoints,
                        )
                    )
                }
                callback(Result.success(devices))
            }
        })
    }

    private fun parseEndpoints(arr: JSONArray?): List<Endpoint> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Endpoint>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val role = e.optString("role", "").takeIf { it.isNotBlank() } ?: continue
            out.add(
                Endpoint(
                    role = role,
                    name = e.optString("name", e.optString("label", role)),
                    enabled = e.optBoolean("enabled", true),
                    hasPower = e.optBoolean("has_power", false),
                    hasPressure = e.optBoolean("has_pressure", false),
                    hasGarageDoor = e.optBoolean("has_garage_door", false),
                    power = e.optString("power", "OFF"),
                    pressurePsi = if (e.isNull("pressure_psi")) null else e.optDouble("pressure_psi"),
                    doorCooldownRemaining = e.optInt("door_cooldown_remaining", 0),
                )
            )
        }
        return out
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

    fun unpairDevice(
        token: String,
        devId: String,
        devSerial: String,
        callback: (Result<String>) -> Unit
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_unpair.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (json.optBoolean("ok")) {
                    callback(Result.success(json.optString("message", "Device unpaired")))
                } else {
                    callback(Result.failure(Exception(json.optString("error", "Unpair failed"))))
                }
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

    fun setDisplayName(
        token: String,
        devId: String,
        devSerial: String,
        displayName: String,
        callback: (Result<String>) -> Unit
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .put("display_name", displayName)
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_settings.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Save failed"))))
                    return
                }
                val name = json.optJSONObject("data")?.optString("name")
                    ?: displayName.ifBlank { DeviceRegistry.configFor(devId).label }
                callback(Result.success(name))
            }
        })
    }

    fun getDeviceSettings(
        token: String,
        devId: String,
        devSerial: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        val request = Request.Builder()
            .url("$BASE/devices_settings.php?dev_id=$devId&dev_serial=$devSerial")
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
                callback(Result.success(json.optJSONObject("data") ?: JSONObject()))
            }
        })
    }

    fun setEndpointPrefs(
        token: String,
        devId: String,
        devSerial: String,
        enabledByRole: Map<String, Boolean>,
        callback: (Result<JSONObject>) -> Unit
    ) {
        val endpoints = JSONObject()
        enabledByRole.forEach { (role, enabled) -> endpoints.put(role, enabled) }
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .put("endpoints", endpoints)
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_settings.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Save failed"))))
                    return
                }
                callback(Result.success(json.optJSONObject("data") ?: JSONObject()))
            }
        })
    }

    /**
     * Set compressor shut-off (cut-out) PSI. Server/device snap to 40–90 in steps of 5.
     * Cut-in is always cut-out − 20 on the device.
     */
    fun setCutOut(
        token: String,
        devId: String,
        devSerial: String,
        cutOutPsi: Int,
        role: String? = null,
        callback: (Result<Int>) -> Unit,
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .put("action", "SET_CUTOUT")
            .put("cut_out_psi", cutOutPsi)
        if (!role.isNullOrBlank()) {
            payload.put("role", role)
        }
        val body = payload.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$BASE/devices_control.php")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Set pressure failed"))))
                    return
                }
                val data = json.optJSONObject("data")
                val cut = data?.optInt("cut_out_psi", cutOutPsi) ?: cutOutPsi
                callback(Result.success(cut))
            }
        })
    }

    fun setPower(
        token: String,
        devId: String,
        devSerial: String,
        on: Boolean,
        role: String? = null,
        callback: (Result<String>) -> Unit,
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .put("action", if (on) "ON" else "OFF")
        if (!role.isNullOrBlank()) {
            payload.put("role", role)
        }
        val body = payload.toString().toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_control.php")
            .header("Authorization", "Bearer $token")
            .post(body)
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

    fun pulseGarageDoor(
        token: String,
        devId: String,
        devSerial: String,
        role: String? = null,
        callback: (Result<Int>) -> Unit,
    ) {
        val payload = JSONObject()
            .put("dev_id", devId)
            .put("dev_serial", devSerial)
            .put("action", "DOOR_PULSE")
        if (!role.isNullOrBlank()) {
            payload.put("role", role)
        }
        val body = payload.toString().toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/devices_control.php")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (response.code == 429) {
                    val retryAfter = json.optInt("retry_after", 30)
                    callback(
                        Result.failure(
                            DoorCooldownException(
                                retryAfter,
                                json.optString("error", "Garage door cooldown active"),
                            )
                        )
                    )
                    return
                }
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Request failed"))))
                    return
                }
                val data = json.optJSONObject("data")
                val cooldown = data?.optInt("retry_after")
                    ?: data?.optInt("cooldown_seconds")
                    ?: 30
                callback(Result.success(cooldown))
            }
        })
    }

    fun getAccountProfile(token: String, callback: (Result<AccountProfile>) -> Unit) {
        val request = Request.Builder()
            .url("$BASE/account_profile.php")
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
                val data = json.getJSONObject("data")
                callback(
                    Result.success(
                        AccountProfile(
                            email = data.getString("email"),
                            username = data.getString("username"),
                            createdAt = data.getString("created_at"),
                            deviceCount = data.getInt("device_count")
                        )
                    )
                )
            }
        })
    }

    fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
        confirmPassword: String,
        callback: (Result<String>) -> Unit
    ) {
        val payload = JSONObject()
            .put("current_password", currentPassword)
            .put("new_password", newPassword)
            .put("confirm_password", confirmPassword)
            .toString()
            .toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$BASE/account_password.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (json.optBoolean("ok")) {
                    callback(Result.success(json.optString("message", "Password updated")))
                } else {
                    callback(Result.failure(Exception(json.optString("error", "Password change failed"))))
                }
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

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                callback(Result.success(parseJson(response)))
            }
        })
    }

    /**
     * Home assistant chat (Evo-X Ollama + tools via cloud).
     * POST api/llm_chat.php
     */
    fun llmChat(
        token: String,
        message: String,
        threadId: String? = null,
        newThread: Boolean = false,
        callback: (Result<LlmChatResult>) -> Unit,
    ) {
        val payload = JSONObject()
            .put("message", message)
            .put("stream", false)
        if (newThread) {
            payload.put("new_thread", true)
        } else if (!threadId.isNullOrBlank()) {
            payload.put("thread_id", threadId)
        }
        val body = payload.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$BASE/llm_chat.php")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(body)
            .build()

        llmClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    val err = json.optString("error", "Assistant request failed")
                    val code = response.code
                    callback(
                        Result.failure(
                            Exception(if (code in 400..599) "$err (HTTP $code)" else err)
                        )
                    )
                    return
                }
                val data = json.optJSONObject("data") ?: JSONObject()
                val tools = mutableListOf<String>()
                val arr = data.optJSONArray("tools_used")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.optString(i, "").trim()
                        if (t.isNotEmpty()) tools.add(t)
                    }
                }
                val modelRaw = if (data.isNull("model")) "" else data.optString("model", "")
                val threadRaw = if (data.isNull("thread_id")) "" else data.optString("thread_id", "")
                callback(
                    Result.success(
                        LlmChatResult(
                            reply = data.optString("reply", "").ifBlank {
                                data.optString("message", "No reply")
                            },
                            model = modelRaw.takeIf { it.isNotBlank() },
                            toolsUsed = tools,
                            threadId = threadRaw.takeIf { it.isNotBlank() },
                            latencyMs = if (data.has("latency_ms") && !data.isNull("latency_ms")) {
                                data.optInt("latency_ms")
                            } else {
                                null
                            },
                            needsConfirm = data.optBoolean("needs_confirm", false),
                            agentRounds = if (data.has("agent_rounds") && !data.isNull("agent_rounds")) {
                                data.optInt("agent_rounds")
                            } else {
                                null
                            },
                        )
                    )
                )
            }
        })
    }

    fun llmHistoryLoad(
        token: String,
        threadId: String? = null,
        callback: (Result<Pair<String?, List<LlmMessage>>>) -> Unit,
    ) {
        val url = if (!threadId.isNullOrBlank()) {
            "$BASE/llm_history.php?thread_id=$threadId"
        } else {
            "$BASE/llm_history.php"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    // History optional — treat as empty rather than hard fail
                    if (response.code == 503) {
                        callback(Result.success(null to emptyList()))
                        return
                    }
                    callback(Result.failure(Exception(json.optString("error", "History failed"))))
                    return
                }
                val data = json.optJSONObject("data") ?: JSONObject()
                val tid = if (data.isNull("thread_id")) null
                else data.optString("thread_id", "").takeIf { it.isNotBlank() }
                val msgs = mutableListOf<LlmMessage>()
                val arr = data.optJSONArray("messages")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val role = m.optString("role", "")
                        val content = m.optString("content", "")
                        if (role.isBlank() || content.isBlank()) continue
                        if (role != "user" && role != "assistant") continue
                        val created = if (m.isNull("created_at")) null
                        else m.optString("created_at", "").takeIf { it.isNotBlank() }
                        msgs.add(
                            LlmMessage(
                                role = role,
                                content = content,
                                createdAt = created,
                            )
                        )
                    }
                }
                callback(Result.success(tid to msgs))
            }
        })
    }

    fun mcpListServers(token: String, callback: (Result<List<McpServer>>) -> Unit) {
        val request = Request.Builder()
            .url("$BASE/mcp_servers.php")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "MCP list failed"))))
                    return
                }
                val arr = json.optJSONObject("data")?.optJSONArray("servers") ?: JSONArray()
                val out = mutableListOf<McpServer>()
                for (i in 0 until arr.length()) {
                    parseMcpServer(arr.optJSONObject(i))?.let { out.add(it) }
                }
                callback(Result.success(out))
            }
        })
    }

    fun mcpCreateServer(
        token: String,
        name: String,
        transport: String,
        url: String,
        command: String,
        authToken: String,
        enabled: Boolean,
        requireConfirm: Boolean,
        callback: (Result<McpServer>) -> Unit,
    ) {
        val payload = JSONObject()
            .put("action", "create")
            .put("name", name)
            .put("transport", transport)
            .put("url", url)
            .put("command", command)
            .put("enabled", enabled)
            .put("require_confirm", requireConfirm)
        if (authToken.isNotBlank()) payload.put("auth_token", authToken)
        mcpPostServer(token, payload, callback)
    }

    fun mcpUpdateServer(
        token: String,
        id: Int,
        name: String,
        transport: String,
        url: String,
        command: String,
        authToken: String?,
        enabled: Boolean,
        requireConfirm: Boolean,
        callback: (Result<McpServer>) -> Unit,
    ) {
        val payload = JSONObject()
            .put("action", "update")
            .put("id", id)
            .put("name", name)
            .put("transport", transport)
            .put("url", url)
            .put("command", command)
            .put("enabled", enabled)
            .put("require_confirm", requireConfirm)
        // null = leave token unchanged on server; empty string would clear if sent
        if (authToken != null) payload.put("auth_token", authToken)
        mcpPostServer(token, payload, callback)
    }

    fun mcpDeleteServer(token: String, id: Int, callback: (Result<Boolean>) -> Unit) {
        val payload = JSONObject().put("action", "delete").put("id", id)
            .toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$BASE/mcp_servers.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Delete failed"))))
                    return
                }
                callback(Result.success(true))
            }
        })
    }

    fun mcpTestServer(token: String, id: Int, callback: (Result<McpServer>) -> Unit) {
        val payload = JSONObject().put("action", "test").put("id", id)
            .toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$BASE/mcp_servers.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()
        // tests can take a while
        llmClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Test failed"))))
                    return
                }
                val server = json.optJSONObject("data")?.optJSONObject("server")
                    ?: json.optJSONObject("data")
                val parsed = parseMcpServer(server)
                if (parsed == null) {
                    callback(Result.failure(Exception("Invalid server response")))
                } else {
                    callback(Result.success(parsed))
                }
            }
        })
    }

    private fun mcpPostServer(
        token: String,
        payload: JSONObject,
        callback: (Result<McpServer>) -> Unit,
    ) {
        val request = Request.Builder()
            .url("$BASE/mcp_servers.php")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        llmClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    callback(Result.failure(Exception(json.optString("error", "Save failed"))))
                    return
                }
                val server = json.optJSONObject("data")?.optJSONObject("server")
                    ?: json.optJSONObject("data")
                val parsed = parseMcpServer(server)
                if (parsed == null) {
                    callback(Result.failure(Exception("Invalid server response")))
                } else {
                    callback(Result.success(parsed))
                }
            }
        })
    }

    private fun parseMcpServer(o: JSONObject?): McpServer? {
        if (o == null) return null
        val id = o.optInt("id", 0)
        if (id <= 0) return null
        val tools = mutableListOf<McpTool>()
        val arr = o.optJSONArray("tools")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val n = t.optString("name", "").trim()
                if (n.isEmpty()) continue
                tools.add(McpTool(n, t.optString("description", "")))
            }
        }
        return McpServer(
            id = id,
            name = o.optString("name", ""),
            slug = o.optString("slug", ""),
            transport = o.optString("transport", "sse"),
            url = o.optString("url", ""),
            command = o.optString("command", ""),
            enabled = o.optBoolean("enabled", true),
            requireConfirm = o.optBoolean("require_confirm", true),
            timeoutSec = o.optInt("timeout_sec", 60),
            toolsCount = o.optInt("tools_count", tools.size),
            lastError = if (o.isNull("last_error")) null
            else o.optString("last_error", "").takeIf { it.isNotBlank() },
            hasAuthToken = o.optBoolean("has_auth_token", false),
            tools = tools,
        )
    }

    fun llmHistoryNewThread(token: String, callback: (Result<String?>) -> Unit) {
        val payload = JSONObject().put("action", "new").toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$BASE/llm_history.php")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                val json = parseJson(response)
                if (!json.optBoolean("ok")) {
                    if (response.code == 503) {
                        callback(Result.success(null))
                        return
                    }
                    callback(Result.failure(Exception(json.optString("error", "New thread failed"))))
                    return
                }
                val data = json.optJSONObject("data")
                val tid = if (data == null || data.isNull("thread_id")) null
                else data.optString("thread_id", "").takeIf { it.isNotBlank() }
                callback(Result.success(tid))
            }
        })
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
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "Invalid response")
        }
    }
}
