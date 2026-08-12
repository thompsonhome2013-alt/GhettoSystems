package com.ghettosystems.v2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * Manage MCP servers (plug-and-play tools for the assistant) without coding.
 */
class McpServersActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var adapter: McpAdapter
    private lateinit var empty: TextView
    private val transports = listOf("sse", "http", "streamable_http", "stdio")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            finish()
            return
        }
        setContentView(R.layout.activity_mcp_servers)

        empty = findViewById(R.id.tv_empty)
        adapter = McpAdapter(
            onTest = { testServer(it) },
            onEdit = { showEditor(it) },
            onDelete = { confirmDelete(it) },
        )
        findViewById<RecyclerView>(R.id.recycler_mcp).apply {
            layoutManager = LinearLayoutManager(this@McpServersActivity)
            adapter = this@McpServersActivity.adapter
        }
        findViewById<TextView>(R.id.tv_back).setOnClickListener { finish() }
        findViewById<FloatingActionButton>(R.id.fab_add_mcp).setOnClickListener {
            showEditor(null)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val token = session.token ?: return
        Gs2Api.mcpListServers(token) { result ->
            runOnUiThread {
                result.onSuccess { list ->
                    adapter.submit(list)
                    empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }.onFailure {
                    val msg = it.message ?: "Load failed"
                    if (AuthHelper.isAuthError(msg)) {
                        AuthHelper.handleAuthFailure(this, session)
                        return@onFailure
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testServer(server: Gs2Api.McpServer) {
        val token = session.token ?: return
        Toast.makeText(this, R.string.mcp_testing, Toast.LENGTH_SHORT).show()
        Gs2Api.mcpTestServer(token, server.id) { result ->
            runOnUiThread {
                result.onSuccess { updated ->
                    Toast.makeText(
                        this,
                        getString(R.string.mcp_test_ok, updated.toolsCount),
                        Toast.LENGTH_LONG,
                    ).show()
                    reload()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Test failed", Toast.LENGTH_LONG).show()
                    reload()
                }
            }
        }
    }

    private fun confirmDelete(server: Gs2Api.McpServer) {
        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems_Dialog)
            .setTitle(R.string.mcp_delete)
            .setMessage(getString(R.string.mcp_delete_confirm, server.name))
            .setPositiveButton(R.string.mcp_delete) { _, _ ->
                val token = session.token ?: return@setPositiveButton
                Gs2Api.mcpDeleteServer(token, server.id) { result ->
                    runOnUiThread {
                        result.onSuccess { reload() }
                            .onFailure {
                                Toast.makeText(this, it.message ?: "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditor(existing: Gs2Api.McpServer?) {
        val view = layoutInflater.inflate(R.layout.dialog_mcp_server, null)
        val etName = view.findViewById<TextInputEditText>(R.id.et_name)
        val etUrl = view.findViewById<TextInputEditText>(R.id.et_url)
        val etCommand = view.findViewById<TextInputEditText>(R.id.et_command)
        val etToken = view.findViewById<TextInputEditText>(R.id.et_token)
        val spinner = view.findViewById<Spinner>(R.id.spinner_transport)
        val swEnabled = view.findViewById<SwitchMaterial>(R.id.switch_enabled)
        val swConfirm = view.findViewById<SwitchMaterial>(R.id.switch_confirm)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            transports,
        )

        if (existing != null) {
            etName.setText(existing.name)
            etUrl.setText(existing.url)
            etCommand.setText(existing.command)
            swEnabled.isChecked = existing.enabled
            swConfirm.isChecked = existing.requireConfirm
            val idx = transports.indexOf(existing.transport).coerceAtLeast(0)
            spinner.setSelection(idx)
            etToken.hint = if (existing.hasAuthToken) getString(R.string.mcp_token_keep) else null
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems_Dialog)
            .setTitle(if (existing == null) R.string.mcp_add else R.string.mcp_edit)
            .setView(view)
            .setPositiveButton(R.string.mcp_save) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val transport = transports.getOrElse(spinner.selectedItemPosition) { "sse" }
                val url = etUrl.text?.toString()?.trim().orEmpty()
                val command = etCommand.text?.toString()?.trim().orEmpty()
                val tokenVal = etToken.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.mcp_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val token = session.token ?: return@setPositiveButton
                if (existing == null) {
                    Gs2Api.mcpCreateServer(
                        token = token,
                        name = name,
                        transport = transport,
                        url = url,
                        command = command,
                        authToken = tokenVal,
                        enabled = swEnabled.isChecked,
                        requireConfirm = swConfirm.isChecked,
                    ) { result ->
                        runOnUiThread {
                            result.onSuccess {
                                Toast.makeText(this, R.string.mcp_saved, Toast.LENGTH_SHORT).show()
                                reload()
                            }.onFailure {
                                Toast.makeText(this, it.message ?: "Save failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    Gs2Api.mcpUpdateServer(
                        token = token,
                        id = existing.id,
                        name = name,
                        transport = transport,
                        url = url,
                        command = command,
                        // blank keeps existing token on server (omit field via null)
                        authToken = tokenVal.takeIf { it.isNotEmpty() },
                        enabled = swEnabled.isChecked,
                        requireConfirm = swConfirm.isChecked,
                    ) { result ->
                        runOnUiThread {
                            result.onSuccess {
                                Toast.makeText(this, R.string.mcp_saved, Toast.LENGTH_SHORT).show()
                                reload()
                            }.onFailure {
                                Toast.makeText(this, it.message ?: "Save failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class McpAdapter(
        private val onTest: (Gs2Api.McpServer) -> Unit,
        private val onEdit: (Gs2Api.McpServer) -> Unit,
        private val onDelete: (Gs2Api.McpServer) -> Unit,
    ) : RecyclerView.Adapter<McpAdapter.VH>() {

        private val items = mutableListOf<Gs2Api.McpServer>()

        fun submit(list: List<Gs2Api.McpServer>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mcp_server, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], onTest, onEdit, onDelete)
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.tv_name)
            private val meta: TextView = view.findViewById(R.id.tv_meta)
            private val tools: TextView = view.findViewById(R.id.tv_tools)
            private val error: TextView = view.findViewById(R.id.tv_error)
            fun bind(
                s: Gs2Api.McpServer,
                onTest: (Gs2Api.McpServer) -> Unit,
                onEdit: (Gs2Api.McpServer) -> Unit,
                onDelete: (Gs2Api.McpServer) -> Unit,
            ) {
                name.text = "${s.name} (${s.slug})"
                val endpoint = if (s.transport == "stdio") s.command else s.url
                meta.text = "${s.transport} · ${if (s.enabled) "on" else "off"} · $endpoint"
                val toolNames = s.tools.take(8).joinToString(", ") { it.name }
                tools.text = if (s.toolsCount > 0) {
                    "Tools (${s.toolsCount}): ${toolNames.ifBlank { "cached" }}"
                } else {
                    "Tools: none yet — tap Test"
                }
                if (!s.lastError.isNullOrBlank()) {
                    error.visibility = View.VISIBLE
                    error.text = s.lastError
                } else {
                    error.visibility = View.GONE
                }
                itemView.findViewById<View>(R.id.btn_test).setOnClickListener { onTest(s) }
                itemView.findViewById<View>(R.id.btn_edit).setOnClickListener { onEdit(s) }
                itemView.findViewById<View>(R.id.btn_delete).setOnClickListener { onDelete(s) }
            }
        }
    }
}
