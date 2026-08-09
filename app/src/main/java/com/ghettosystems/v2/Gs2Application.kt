package com.ghettosystems.v2

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Tracks whole-app foreground/background so a 5-minute exit requires login again.
 * In-app navigation (home → settings, etc.) does not count as leaving.
 */
class Gs2Application : Application(), DefaultLifecycleObserver {

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        SessionManager(this).onAppBackgrounded()
    }

    override fun onStart(owner: LifecycleOwner) {
        val session = SessionManager(this)
        if (session.onAppForegrounded()) {
            startActivity(
                Intent(this, SplashActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            )
        }
    }
}
