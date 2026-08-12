package com.ghettosystems.v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatUiMessage(
    val role: String,
    val content: String,
    val meta: String? = null,
)

class ChatMessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ChatUiMessage>()

    fun submit(list: List<ChatUiMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(msg: ChatUiMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun replaceLast(msg: ChatUiMessage) {
        if (items.isEmpty()) {
            append(msg)
            return
        }
        items[items.lastIndex] = msg
        notifyItemChanged(items.lastIndex)
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].role == "user") TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))
        } else {
            AssistantVH(inflater.inflate(R.layout.item_chat_assistant, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is UserVH -> holder.bind(item)
            is AssistantVH -> holder.bind(item)
        }
    }

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: TextView = view.findViewById(R.id.tv_bubble)
        fun bind(item: ChatUiMessage) {
            bubble.text = item.content
        }
    }

    class AssistantVH(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: TextView = view.findViewById(R.id.tv_bubble)
        private val meta: TextView = view.findViewById(R.id.tv_meta)
        fun bind(item: ChatUiMessage) {
            bubble.text = item.content
            if (item.meta.isNullOrBlank()) {
                meta.visibility = View.GONE
            } else {
                meta.visibility = View.VISIBLE
                meta.text = item.meta
            }
        }
    }

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_ASSISTANT = 2
    }
}
