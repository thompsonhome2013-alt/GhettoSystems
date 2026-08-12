package com.ghettosystems.v2

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Home assistant chat UI — talks to cloud llm_chat (Evo-X Ollama + tools).
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var adapter: ChatMessageAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var typingRow: LinearLayout
    private lateinit var tvStatus: TextView

    private var threadId: String? = null
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            finish()
            return
        }

        setContentView(R.layout.activity_assistant)

        recycler = findViewById(R.id.recycler_messages)
        etMessage = findViewById(R.id.et_message)
        btnSend = findViewById(R.id.btn_send)
        typingRow = findViewById(R.id.typing_row)
        tvStatus = findViewById(R.id.tv_assistant_status)

        adapter = ChatMessageAdapter()
        recycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recycler.adapter = adapter

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_new_chat).setOnClickListener { confirmNewChat() }

        btnSend.setOnClickListener { sendCurrent() }
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent()
                true
            } else {
                false
            }
        }

        // Welcome bubble while history loads
        adapter.submit(
            listOf(
                ChatUiMessage(
                    role = "assistant",
                    content = getString(R.string.assistant_welcome),
                    meta = getString(R.string.assistant_welcome_meta),
                )
            )
        )

        loadHistory()
    }

    private fun loadHistory() {
        val token = session.token ?: return
        tvStatus.text = getString(R.string.assistant_loading_history)
        Gs2Api.llmHistoryLoad(token, null) { result ->
            runOnUiThread {
                result.onSuccess { (tid, messages) ->
                    threadId = tid
                    if (messages.isNotEmpty()) {
                        adapter.submit(
                            messages.map {
                                ChatUiMessage(
                                    role = it.role,
                                    content = it.content,
                                    meta = null,
                                )
                            }
                        )
                        scrollToEnd()
                    }
                    tvStatus.text = getString(R.string.assistant_subtitle)
                }.onFailure {
                    tvStatus.text = getString(R.string.assistant_subtitle)
                }
            }
        }
    }

    private fun confirmNewChat() {
        MaterialAlertDialogBuilder(this, R.style.Theme_GhettoSystems_Dialog)
            .setTitle(R.string.assistant_new_chat)
            .setMessage(R.string.assistant_new_chat_confirm)
            .setPositiveButton(R.string.assistant_new_chat) { _, _ -> startNewChat() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startNewChat() {
        val token = session.token ?: return
        Gs2Api.llmHistoryNewThread(token) { result ->
            runOnUiThread {
                result.onSuccess { tid ->
                    threadId = tid
                    adapter.submit(
                        listOf(
                            ChatUiMessage(
                                role = "assistant",
                                content = getString(R.string.assistant_welcome),
                                meta = getString(R.string.assistant_new_thread_meta),
                            )
                        )
                    )
                    tvStatus.text = getString(R.string.assistant_subtitle)
                }.onFailure {
                    // Local-only new chat if server history unavailable
                    threadId = null
                    adapter.submit(
                        listOf(
                            ChatUiMessage(
                                role = "assistant",
                                content = getString(R.string.assistant_welcome),
                                meta = getString(R.string.assistant_new_thread_meta),
                            )
                        )
                    )
                }
            }
        }
    }

    private fun sendCurrent() {
        if (sending) return
        val text = etMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val token = session.token
        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.assistant_not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }

        etMessage.setText("")
        adapter.append(ChatUiMessage(role = "user", content = text))
        scrollToEnd()
        setSending(true)

        Gs2Api.llmChat(
            token = token,
            message = text,
            threadId = threadId,
            newThread = false,
        ) { result ->
            runOnUiThread {
                setSending(false)
                result.onSuccess { res ->
                    if (!res.threadId.isNullOrBlank()) {
                        threadId = res.threadId
                    }
                    val metaParts = mutableListOf<String>()
                    res.model?.let { metaParts.add(it) }
                    if (res.toolsUsed.isNotEmpty()) {
                        metaParts.add("tools: " + res.toolsUsed.joinToString(", "))
                    }
                    res.latencyMs?.let { metaParts.add("${it}ms") }
                    if (res.needsConfirm) {
                        metaParts.add("needs confirm")
                    }
                    adapter.append(
                        ChatUiMessage(
                            role = "assistant",
                            content = res.reply,
                            meta = metaParts.joinToString(" · ").ifBlank { null },
                        )
                    )
                    scrollToEnd()
                    tvStatus.text = getString(R.string.assistant_subtitle)
                }.onFailure { e ->
                    adapter.append(
                        ChatUiMessage(
                            role = "assistant",
                            content = getString(
                                R.string.assistant_error,
                                e.message ?: "unknown error",
                            ),
                            meta = "error",
                        )
                    )
                    scrollToEnd()
                    tvStatus.text = getString(R.string.assistant_subtitle)
                }
            }
        }
    }

    private fun setSending(value: Boolean) {
        sending = value
        btnSend.isEnabled = !value
        etMessage.isEnabled = !value
        typingRow.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            tvStatus.text = getString(R.string.assistant_thinking)
        }
    }

    private fun scrollToEnd() {
        recycler.post {
            val n = adapter.itemCount
            if (n > 0) recycler.scrollToPosition(n - 1)
        }
    }
}
