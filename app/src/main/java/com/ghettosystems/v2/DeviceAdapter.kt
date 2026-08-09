package com.ghettosystems.v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.Locale

data class DoorCardState(
    val cooldownRemaining: Int = 0,
    val busy: Boolean = false,
    val statusMessage: String? = null,
)

class DeviceAdapter(
    private val onSettingsClick: (Gs2Api.Device) -> Unit,
    private val onPowerToggle: (Gs2Api.Device) -> Unit,
    private val onPressureClick: (Gs2Api.Device) -> Unit,
    private val onGarageDoorClick: (Gs2Api.Device) -> Unit,
    private val doorStateFor: (device: Gs2Api.Device) -> DoorCardState,
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private val items = mutableListOf<Gs2Api.Device>()
    private val powerBusyKeys = mutableSetOf<String>()

    fun submit(devices: List<Gs2Api.Device>) {
        items.clear()
        items.addAll(devices)
        notifyDataSetChanged()
    }

    fun setPowerBusy(device: Gs2Api.Device, busy: Boolean) {
        val key = deviceKey(device)
        if (busy) {
            powerBusyKeys.add(key)
        } else {
            powerBusyKeys.remove(key)
        }
        notifyDeviceChanged(device)
    }

    fun applyPowerState(device: Gs2Api.Device, power: String) {
        val index = items.indexOfFirst { sameLogical(it, device) }
        if (index < 0) return
        val current = items[index]
        items[index] = current.copy(power = power.uppercase(Locale.US))
        notifyItemChanged(index)
    }

    fun notifyDeviceChanged(device: Gs2Api.Device) {
        val index = items.indexOfFirst { sameLogical(it, device) }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun notifyDeviceKey(key: String) {
        val index = items.indexOfFirst { deviceKey(it) == key }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val device = items[position]
        val card = DeviceRegistry.configFor(device.devId, device.role).card
        return layoutForCard(card)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return Holder(view, viewType)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            device = items[position],
            onSettingsClick = onSettingsClick,
            onPowerToggle = onPowerToggle,
            onPressureClick = onPressureClick,
            onGarageDoorClick = onGarageDoorClick,
            powerBusyKeys = powerBusyKeys,
            doorStateFor = doorStateFor,
        )
    }

    override fun getItemCount() = items.size

    private fun layoutForCard(card: String): Int {
        return when (card.lowercase(Locale.US)) {
            CARD_GARAGE, CARD_COMPRESSOR, CARD_DOOR -> R.layout.item_device_garage
            else -> R.layout.item_device_generic
        }
    }

    class Holder(itemView: View, private val layoutId: Int) : RecyclerView.ViewHolder(itemView) {
        private val tvName = itemView.findViewById<TextView>(R.id.tv_name)
        private val tvStatus = itemView.findViewById<TextView>(R.id.tv_status)
        private val statusPill = itemView.findViewById<LinearLayout>(R.id.status_pill)
        private val statusDot = itemView.findViewById<View>(R.id.status_dot)
        private val btnSettings = itemView.findViewById<ImageButton>(R.id.btn_device_settings)

        fun bind(
            device: Gs2Api.Device,
            onSettingsClick: (Gs2Api.Device) -> Unit,
            onPowerToggle: (Gs2Api.Device) -> Unit,
            onPressureClick: (Gs2Api.Device) -> Unit,
            onGarageDoorClick: (Gs2Api.Device) -> Unit,
            powerBusyKeys: Set<String>,
            doorStateFor: (device: Gs2Api.Device) -> DoorCardState,
        ) {
            tvName.text = device.displayName?.takeIf { it.isNotBlank() } ?: device.name
            bindCardStats(
                device,
                onPowerToggle,
                onPressureClick,
                onGarageDoorClick,
                powerBusyKeys,
                doorStateFor,
            )
            bindOnlineStatus(device)
            btnSettings.setOnClickListener { onSettingsClick(device) }
        }

        private fun bindCardStats(
            device: Gs2Api.Device,
            onPowerToggle: (Gs2Api.Device) -> Unit,
            onPressureClick: (Gs2Api.Device) -> Unit,
            onGarageDoorClick: (Gs2Api.Device) -> Unit,
            powerBusyKeys: Set<String>,
            doorStateFor: (device: Gs2Api.Device) -> DoorCardState,
        ) {
            val power = if (device.online) device.power.uppercase(Locale.US) else "OFF"
            val config = DeviceRegistry.configFor(device.devId, device.role)
            when (layoutId) {
                R.layout.item_device_garage -> {
                    val powerRow = itemView.findViewById<View>(R.id.widget_power_button).parent as View
                    val powerButton = itemView.findViewById<LinearLayout>(R.id.widget_power_button)
                    val powerValue = itemView.findViewById<TextView>(R.id.tv_widget_power)
                    val pressureValue = itemView.findViewById<TextView>(R.id.tv_widget_pressure)
                    val subtitleAir = itemView.findViewById<TextView?>(R.id.tv_subtitle_air)

                    // Hub card: show Air Compressor subtitle when compressor section is active.
                    val showCompressor = device.showCompressor() ||
                        (device.endpoints.isEmpty() && config.hasPower)
                    val showDoor = device.showDoor() ||
                        (device.endpoints.isEmpty() && config.hasGarageDoor)

                    subtitleAir?.visibility = if (showCompressor) View.VISIBLE else View.GONE
                    powerRow.visibility = if (showCompressor) View.VISIBLE else View.GONE

                    if (showCompressor) {
                        val isOn = power == "ON"
                        val busy = powerBusyKeys.contains(deviceKey(device))

                        powerValue.text = if (busy) "…" else power
                        powerButton.setBackgroundResource(
                            if (isOn) R.drawable.bg_widget_stat_power_on else R.drawable.bg_widget_stat_power
                        )
                        powerValue.setTextColor(
                            itemView.context.getColor(
                                if (isOn) R.color.colorPrimary else R.color.colorOnBackground
                            )
                        )

                        val canToggle = device.online && !busy
                        powerButton.isEnabled = canToggle
                        powerButton.alpha = if (device.online) 1f else 0.55f
                        powerButton.setOnClickListener {
                            if (!device.online) {
                                Toast.makeText(
                                    itemView.context,
                                    R.string.power_toggle_offline,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@setOnClickListener
                            }
                            onPowerToggle(device)
                        }

                        pressureValue.text =
                            if (device.online && device.pressurePsi != null) {
                                // Whole PSI only — matches device smoothing (no decimals)
                                String.format(Locale.US, "%d PSI", Math.round(device.pressurePsi))
                            } else {
                                "—"
                            }
                        val pressureRow = pressureValue.parent as? View
                        pressureRow?.visibility = View.VISIBLE
                        // Tap pressure to set shut-off (cut-out) on the device
                        pressureRow?.isClickable = true
                        pressureRow?.isFocusable = true
                        pressureRow?.alpha = if (device.online) 1f else 0.55f
                        pressureRow?.setOnClickListener {
                            if (!device.online) {
                                Toast.makeText(
                                    itemView.context,
                                    R.string.power_toggle_offline,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@setOnClickListener
                            }
                            onPressureClick(device)
                        }
                    }

                    bindGarageDoor(device, onGarageDoorClick, doorStateFor, showDoor)
                }
                R.layout.item_device_generic -> {
                    itemView.findViewById<TextView>(R.id.tv_widget_status).text = power
                }
            }
        }

        private fun bindGarageDoor(
            device: Gs2Api.Device,
            onGarageDoorClick: (Gs2Api.Device) -> Unit,
            doorStateFor: (device: Gs2Api.Device) -> DoorCardState,
            showDoor: Boolean,
        ) {
            val section = itemView.findViewById<View>(R.id.section_garage_door)
            val btnGarageDoor = itemView.findViewById<MaterialButton>(R.id.btn_garage_door)
            val tvDoorStatus = itemView.findViewById<TextView>(R.id.tv_door_status)
            val subtitleDoor = itemView.findViewById<TextView?>(R.id.tv_subtitle_door)

            if (!showDoor) {
                section.visibility = View.GONE
                return
            }

            section.visibility = View.VISIBLE
            subtitleDoor?.visibility = View.VISIBLE

            val state = doorStateFor(device)
            val enabled = device.online && !state.busy && state.cooldownRemaining <= 0

            if (state.cooldownRemaining > 0) {
                btnGarageDoor.text = itemView.context.getString(
                    R.string.garage_door_cooldown,
                    state.cooldownRemaining,
                )
                if (!state.busy) {
                    tvDoorStatus.text = itemView.context.getString(
                        R.string.garage_door_cooldown_status,
                        state.cooldownRemaining,
                    )
                } else {
                    tvDoorStatus.text = state.statusMessage.orEmpty()
                }
            } else {
                btnGarageDoor.text = itemView.context.getString(R.string.open_garage_door)
                tvDoorStatus.text = if (state.busy) {
                    state.statusMessage.orEmpty()
                } else {
                    ""
                }
            }

            btnGarageDoor.isEnabled = enabled
            btnGarageDoor.alpha = if (enabled) 1f else 0.55f
            btnGarageDoor.setOnClickListener { onGarageDoorClick(device) }
        }

        private fun bindOnlineStatus(device: Gs2Api.Device) {
            if (device.online) {
                statusPill.setBackgroundResource(R.drawable.status_pill_online)
                statusDot.setBackgroundResource(R.drawable.status_dot_online)
                tvStatus.setText(R.string.online)
                tvStatus.setTextColor(itemView.context.getColor(R.color.colorPrimary))
                return
            }

            val useNeutralOffline = layoutId == R.layout.item_device_garage
            statusPill.setBackgroundResource(
                if (useNeutralOffline) {
                    R.drawable.status_pill_offline_neutral
                } else {
                    R.drawable.status_pill_offline
                }
            )
            statusDot.setBackgroundResource(
                if (useNeutralOffline) {
                    R.drawable.status_dot_offline_neutral
                } else {
                    R.drawable.status_dot_offline
                }
            )
            tvStatus.setText(R.string.offline)
            tvStatus.setTextColor(
                itemView.context.getColor(
                    if (useNeutralOffline) R.color.textSecondary else R.color.red_active
                )
            )
        }
    }

    companion object {
        const val CARD_GARAGE = "garage"
        const val CARD_COMPRESSOR = "compressor"
        const val CARD_DOOR = "door"
        const val CARD_GENERIC = "generic"

        fun deviceKey(devId: String, devSerial: String, role: String? = null): String {
            // Hub cards use physical key only.
            return "$devId|$devSerial"
        }

        fun deviceKey(device: Gs2Api.Device): String =
            deviceKey(device.devId, device.devSerial)

        fun sameLogical(a: Gs2Api.Device, b: Gs2Api.Device): Boolean =
            a.devId == b.devId && a.devSerial == b.devSerial
    }
}
