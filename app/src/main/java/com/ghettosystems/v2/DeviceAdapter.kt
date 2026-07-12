package com.ghettosystems.v2

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val onClick: (Gs2Api.Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private val items = mutableListOf<Gs2Api.Device>()

    fun submit(devices: List<Gs2Api.Device>) {
        items.clear()
        items.addAll(devices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount() = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName = itemView.findViewById<TextView>(R.id.tv_name)
        private val tvMeta = itemView.findViewById<TextView>(R.id.tv_meta)
        private val tvStatus = itemView.findViewById<TextView>(R.id.tv_status)

        fun bind(device: Gs2Api.Device, onClick: (Gs2Api.Device) -> Unit) {
            tvName.text = device.name
            tvMeta.text = "${device.devId} · ${device.devSerial}"
            tvStatus.text = if (device.online) "Online" else "Offline"
            tvStatus.setTextColor(Color.parseColor(if (device.online) "#0052FF" else "#EF4444"))
            itemView.setOnClickListener { onClick(device) }
        }
    }
}