package com.ghettosystems.v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

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
        private val tvWidgetPower = itemView.findViewById<TextView>(R.id.tv_widget_power)
        private val tvWidgetPressure = itemView.findViewById<TextView>(R.id.tv_widget_pressure)
        private val statusPill = itemView.findViewById<LinearLayout>(R.id.status_pill)
        private val statusDot = itemView.findViewById<View>(R.id.status_dot)
        private val tvControl = itemView.findViewById<TextView>(R.id.tv_control)

        fun bind(device: Gs2Api.Device, onClick: (Gs2Api.Device) -> Unit) {
            val config = DeviceRegistry.configFor(device.devId)
            tvName.text = config.label
            tvMeta.text = "${device.devId} · ${device.devSerial}"

            val power = if (device.online) device.power.uppercase(Locale.US) else "OFF"
            tvWidgetPower.text = power
            tvWidgetPressure.text = if (device.online && device.pressurePsi != null) {
                String.format(Locale.US, "%.1f PSI", device.pressurePsi)
            } else {
                "—"
            }

            if (device.online) {
                statusPill.setBackgroundResource(R.drawable.status_pill_online)
                statusDot.setBackgroundResource(R.drawable.status_dot_online)
                tvStatus.setText(R.string.online)
                tvStatus.setTextColor(itemView.context.getColor(R.color.colorPrimary))
            } else {
                statusPill.setBackgroundResource(R.drawable.status_pill_offline)
                statusDot.setBackgroundResource(R.drawable.status_dot_offline)
                tvStatus.setText(R.string.offline)
                tvStatus.setTextColor(itemView.context.getColor(R.color.red_active))
            }

            val openDevice = View.OnClickListener { onClick(device) }
            itemView.setOnClickListener(openDevice)
            tvControl.setOnClickListener(openDevice)
        }
    }
}