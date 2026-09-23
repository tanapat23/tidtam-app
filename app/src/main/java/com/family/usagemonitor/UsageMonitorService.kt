package com.family.usagemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import com.family.usagemonitor.data.AppDatabase
import com.family.usagemonitor.data.AppSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service: คอยอ่าน "แอปที่อยู่หน้าจอ" จาก UsageStatsManager แล้ว
 *  - แจ้งเตือนตอนเปิดแอป
 *  - นับเวลาจนออกจากแอป แล้วแจ้งเตือน + บันทึกลงฐานข้อมูล
 *
 * นับเฉพาะแอปที่อยู่เต็มหน้าจอจริง ๆ  → ป๊อปอัพเล็ก (PiP) / นำทาง background ไม่ถูกนับ
 */
class UsageMonitorService : Service() {

    // ค่า eventType: RESUMED = อยู่หน้าจอ, PAUSED = ออกจากหน้าจอ
    // (เท่ากับ MOVE_TO_FOREGROUND=1 / MOVE_TO_BACKGROUND=2 เพื่อรองรับ Android ทุกรุ่น)
    private val RESUMED = 1
    private val PAUSED = 2

    private lateinit var usm: UsageStatsManager
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase
    private lateinit var ignore: Set<String>
    private var telegram: TelegramClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var callTracker: CallTracker? = null

    private var lastQueryTime = 0L

    // แอปที่กำลังเปิดอยู่ตอนนี้
    private var curPkg: String? = null
    private var curLabel: String? = null
    private var curStart = 0L

    // เวลาที่ "เริ่มดูเหมือนจะออก" (ยังไม่ยืนยัน) — ถ้ากลับเข้าแอปเดิมทันจะยกเลิก
    // 0 = กำลังใช้อยู่ปกติ, >0 = อยู่ในช่วงผ่อนผันรอดูว่าจะออกจริงไหม
    private var pauseAt = 0L

    // เมื่อล็อกหน้าจอ = ถือว่าออกจากแอป
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                val closeTs = if (pauseAt > 0L) pauseAt else System.currentTimeMillis()
                scope.launch { finalizeClose(closeTs) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        prefs = Prefs(this)
        db = AppDatabase.get(this)
        ignore = Util.buildIgnoreSet(this)

        startForeground(FG_ID, buildOngoingNotification())
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        // จับเวลาโทรแยกต่างหาก (แม่นยำแม้จอดับ)
        callTracker = CallTracker(this) { type, durationMs ->
            scope.launch { handleCall(type, durationMs) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // โหลดค่าล่าสุด (เผื่อผู้ใช้เพิ่งใส่ token)
        telegram = if (prefs.isConfigured) TelegramClient(prefs.botToken, prefs.chatId) else null

        if (pollJob == null || pollJob?.isActive != true) {
            lastQueryTime = System.currentTimeMillis()
            pollJob = scope.launch { pollLoop() }
            try {
                callTracker?.start()
            } catch (e: Exception) {
                Log.w(TAG, "เริ่ม CallTracker ไม่ได้ (อาจยังไม่ได้ให้สิทธิ์โทรศัพท์)", e)
            }
            scope.launch { telegram?.send("✅ <b>เริ่มติดตามการใช้แอปแล้ว</b>") }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                val now = System.currentTimeMillis()
                processEvents(lastQueryTime, now)
                lastQueryTime = now
                // ถ้าอยู่ในช่วงผ่อนผันแล้วครบเวลา = ออกจริง → ปิดเซสชัน
                if (curPkg != null && pauseAt > 0L && now - pauseAt >= GRACE_MS) {
                    finalizeClose(pauseAt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "pollLoop error", e)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun processEvents(from: Long, to: Long) {
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val ts = e.timeStamp
            when (e.eventType) {
                RESUMED -> {
                    when {
                        // กลับเข้าแอปเดิม (เช่น กดย้อนกลับใน activity เดียวกัน
                        // หรือแวะออกแล้วรีบกลับ) → ยกเลิกการออก ถือว่าใช้ต่อเนื่อง
                        pkg == curPkg -> pauseAt = 0L

                        // ไปหน้าโฮม/ระบบ → ยังไม่ปิดทันที ตั้งช่วงผ่อนผันไว้ก่อน
                        pkg in ignore -> {
                            if (curPkg != null && pauseAt == 0L) pauseAt = ts
                        }

                        // เปลี่ยนไปแอปอื่นจริง → ปิดตัวเก่า แล้วเปิดตัวใหม่
                        else -> {
                            finalizeClose(if (pauseAt > 0L) pauseAt else ts)
                            openApp(pkg, ts)
                        }
                    }
                }
                PAUSED -> {
                    // แอปปัจจุบันหยุดแสดงผล → ตั้งช่วงผ่อนผัน (ยังไม่แจ้ง)
                    if (pkg == curPkg && pauseAt == 0L) pauseAt = ts
                }
            }
        }
    }

    /** มีแอปใหม่ขึ้นมาอยู่หน้าจอ (แจ้งเตือนเปิดแอป) */
    private suspend fun openApp(pkg: String, ts: Long) {
        val label = Util.appLabel(this, pkg)
        curPkg = pkg
        curLabel = label
        curStart = ts
        pauseAt = 0L

        telegram?.send("📱 <b>เปิดแอป</b>: $label\n🕐 ${Util.clock(ts)} น.")
    }

    /** ปิดเซสชันที่กำลังเปิดอยู่ (คำนวณเวลา + บันทึก + แจ้งเตือน) */
    private suspend fun finalizeClose(endTs: Long) {
        val pkg = curPkg ?: return
        val label = curLabel ?: pkg
        val start = curStart
        // เคลียร์สถานะก่อน กันปิดซ้ำ
        curPkg = null
        curLabel = null
        curStart = 0L
        pauseAt = 0L

        val duration = endTs - start
        if (duration < prefs.minSessionMs) return   // สั้นเกินไป ข้าม

        try {
            db.sessionDao().insert(
                AppSession(
                    packageName = pkg,
                    appLabel = label,
                    startTime = start,
                    endTime = endTs,
                    durationMs = duration,
                    dayKey = Util.dayKey(start)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "บันทึก session ล้มเหลว", e)
        }

        if (prefs.notifyOnExit) {
            telegram?.send("❎ <b>ออกจาก</b> $label\n⏱️ ใช้ไป ${Util.humanDuration(duration)}")
        }
    }

    /** จัดการเมื่อจบสายโทร: แจ้ง Telegram + บันทึกลงฐานข้อมูล (แยกจากการใช้แอป) */
    private suspend fun handleCall(type: CallType, durationMs: Long) {
        val now = System.currentTimeMillis()
        val (label, pkg) = when (type) {
            CallType.INCOMING -> "📞 รับสาย" to "__call_in__"
            CallType.OUTGOING -> "📞 โทรออก" to "__call_out__"
            CallType.MISSED -> "📞 สายไม่ได้รับ" to "__call_missed__"
        }

        val message = if (type == CallType.MISSED) {
            "$label\n🕐 ${Util.clock(now)} น."
        } else {
            "$label\n⏱️ คุยไป ${Util.humanDuration(durationMs)}"
        }
        telegram?.send(message)

        try {
            db.sessionDao().insert(
                AppSession(
                    packageName = pkg,
                    appLabel = label,
                    startTime = now - durationMs,
                    endTime = now,
                    durationMs = durationMs,
                    dayKey = Util.dayKey(now)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "บันทึกสายโทรล้มเหลว", e)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "การติดตามการใช้แอป",
            NotificationManager.IMPORTANCE_MIN
        ).apply { description = "แจ้งว่ากำลังติดตามการใช้แอปอยู่" }
        nm.createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("กำลังติดตามการใช้แอป")
            .setContentText("ทำงานอยู่เบื้องหลัง")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        try {
            callTracker?.stop()
        } catch (_: Exception) {
        }
        pollJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "UsageMonitorService"
        private const val CHANNEL_ID = "usage_monitor_fg"
        private const val FG_ID = 1001
        private const val POLL_INTERVAL_MS = 1500L

        // ช่วงผ่อนผัน: ออกจากแอปแล้วกลับเข้าเดิมภายในเวลานี้ = ไม่นับว่าออก
        private const val GRACE_MS = 2500L

        fun start(context: Context) {
            val i = Intent(context, UsageMonitorService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }
}
