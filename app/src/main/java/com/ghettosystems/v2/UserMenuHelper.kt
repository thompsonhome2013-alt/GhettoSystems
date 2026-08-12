package com.ghettosystems.v2

import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

object UserMenuHelper {

    fun bind(
        activity: AppCompatActivity,
        session: SessionManager,
        menuButton: ImageButton,
        highlightAccount: Boolean = false,
        onLogout: () -> Unit
    ) {
        val displayName = session.email?.takeIf { it.isNotBlank() }
            ?: session.username?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.email)

        menuButton.setOnClickListener { anchor ->
            val popupView = LayoutInflater.from(activity).inflate(R.layout.popup_user_menu, null)
            popupView.findViewById<TextView>(R.id.tv_menu_email).text = displayName

            val accountItem = popupView.findViewById<TextView>(R.id.menu_account_settings)

            if (highlightAccount) {
                accountItem.setTextColor(activity.getColor(R.color.colorPrimary))
                accountItem.setBackgroundColor(activity.getColor(R.color.colorBackground))
            }

            val popup = PopupWindow(
                popupView,
                anchor.resources.getDimensionPixelSize(R.dimen.menu_popup_width),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popup.elevation = 16f

            accountItem.setOnClickListener {
                popup.dismiss()
                if (!highlightAccount) {
                    activity.startActivity(Intent(activity, AccountSettingsActivity::class.java))
                }
            }

            popupView.findViewById<TextView>(R.id.menu_assistant)?.setOnClickListener {
                popup.dismiss()
                activity.startActivity(Intent(activity, AssistantActivity::class.java))
            }

            popupView.findViewById<TextView>(R.id.menu_mcp)?.setOnClickListener {
                popup.dismiss()
                activity.startActivity(Intent(activity, McpServersActivity::class.java))
            }

            popupView.findViewById<TextView>(R.id.menu_logout).setOnClickListener {
                popup.dismiss()
                onLogout()
            }

            popup.showAsDropDown(anchor, 0, 8, Gravity.END)
        }
    }
}
