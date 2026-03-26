package com.example.hassiwrapper.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.hassiwrapper.ui.login.LoginActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launch = Intent(context, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }
}
