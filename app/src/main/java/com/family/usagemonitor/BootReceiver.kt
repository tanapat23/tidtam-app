package com.family.usagemonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** เปิด service + ตั้งงานสรุปใหม่อัตโนมัติ หลังเครื่องรีบูต */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = Prefs(context)
            if (prefs.trackingEnabled && prefs.isConfigured && UsageAccess.isGranted(context)) {
                UsageMonitorService.start(context)
                DailySummaryWorker.schedule(context)
            }
        }
    }
}
