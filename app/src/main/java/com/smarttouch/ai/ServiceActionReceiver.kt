package com.smarttouch.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smarttouch.ai.accessibility.TouchAccessibilityService

/**
 * Relays Start/Stop/Pause/Resume commands (from the persistent notification, or from
 * an app shortcut / Bixby Routine) to the running accessibility service. If the
 * accessibility service isn't currently enabled/running, this is a safe no-op -
 * Smart Touch AI never performs touches without the service actively running.
 */
class ServiceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        TouchAccessibilityService.instance?.handleAction(action)
    }
}
