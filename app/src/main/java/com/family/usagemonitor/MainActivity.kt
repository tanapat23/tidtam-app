package com.family.usagemonitor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.family.usagemonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        // โหลดค่าเดิม
        b.editToken.setText(prefs.botToken)
        b.editChatId.setText(prefs.chatId)
        b.switchNotifyExit.isChecked = prefs.notifyOnExit

        b.btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        b.btnNotifPermission.setOnClickListener {
            val perms = mutableListOf(android.Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }

        b.btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        b.btnTest.setOnClickListener { sendTest() }

        b.btnSaveStart.setOnClickListener { saveAndStart() }

        b.btnTestSummary.setOnClickListener { sendTodaySummary() }

        b.btnStop.setOnClickListener {
            prefs.trackingEnabled = false
            UsageMonitorService.stop(this)
            toast("หยุดการติดตามแล้ว")
            refreshStatus()
        }
    }

    private fun sendTodaySummary() {
        if (!prefs.isConfigured) {
            toast("ใส่ Token และ Chat ID ก่อน")
            return
        }
        val today = Util.dayKey(System.currentTimeMillis())
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                DailySummaryWorker.sendSummary(this@MainActivity, today)
            }
            toast(if (ok) "ส่งสรุปของวันนี้แล้ว — เช็ก Telegram" else "ส่งไม่สำเร็จ ตรวจการตั้งค่า/เน็ต")
        }
    }

    override fun onResume() {
        super.onResume()
        // ถ้าเคยสั่งติดตามไว้ + พร้อม → เปิด service ให้เองเมื่อเปิดแอป (กันกรณี service ตาย)
        if (prefs.trackingEnabled && prefs.isConfigured && UsageAccess.isGranted(this)) {
            UsageMonitorService.start(this)
            DailySummaryWorker.schedule(this)
        }
        refreshStatus()
    }

    private fun saveAndStart() {
        prefs.botToken = b.editToken.text.toString()
        prefs.chatId = b.editChatId.text.toString()
        prefs.notifyOnExit = b.switchNotifyExit.isChecked

        if (!prefs.isConfigured) {
            toast("กรุณาใส่ Bot Token และ Chat ID ให้ครบ")
            return
        }
        if (!UsageAccess.isGranted(this)) {
            toast("กรุณาเปิดสิทธิ์ Usage Access ก่อน")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        prefs.trackingEnabled = true
        UsageMonitorService.start(this)
        DailySummaryWorker.schedule(this)
        toast("บันทึกและเริ่มติดตามแล้ว ✅")
        refreshStatus()
    }

    private fun sendTest() {
        val token = b.editToken.text.toString().trim()
        val chat = b.editChatId.text.toString().trim()
        if (token.isEmpty() || chat.isEmpty()) {
            toast("ใส่ Token และ Chat ID ก่อน")
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                TelegramClient(token, chat).send("🔔 ทดสอบการเชื่อมต่อสำเร็จ! แอปพร้อมส่งข้อความแล้ว")
            }
            toast(if (ok) "ส่งทดสอบสำเร็จ — เช็ก Telegram ได้เลย" else "ส่งไม่สำเร็จ ตรวจ Token/Chat ID/เน็ต")
        }
    }

    private fun refreshStatus() {
        val usage = if (UsageAccess.isGranted(this)) "✅ ได้รับแล้ว" else "❌ ยังไม่ได้"
        val config = if (prefs.isConfigured) "✅ ตั้งค่าแล้ว" else "❌ ยังไม่ได้ใส่"
        b.txtStatus.text = "สิทธิ์ Usage Access: $usage\nTelegram: $config"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
