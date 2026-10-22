package com.example.turbo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Session-only guarantee: puts back any vibration settings changed last session. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread { try { Engine.restore(ctx.applicationContext) } finally { pending.finish() } }.start()
    }
}
