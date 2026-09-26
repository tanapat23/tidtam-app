package com.family.usagemonitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.family.usagemonitor.data.AppDatabase
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * งานที่รันประมาณเที่ยงคืน: สรุปการใช้แอปของวันแล้วส่งไป Telegram
 */
class DailySummaryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dayKey = targetDayKey()

        // 1) ส่งสรุปของวันนั้นเข้า Telegram ก่อน (ประวัติเก็บใน Telegram)
        sendSummary(applicationContext, dayKey)

        // 2) แล้วลบข้อมูลของวันนั้นและก่อนหน้าทิ้ง (เก็บเฉพาะวันใหม่) กันเปลืองพื้นที่
        AppDatabase.get(applicationContext)
            .sessionDao().deleteUpToIncluding(dayKey)

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_summary"

        /**
         * เลือกวันที่จะสรุปจาก "เวลาที่รันจริง":
         *  - รันช่วงค่ำ (ก่อนเที่ยงคืน, ชั่วโมง >= 12) → สรุปวันนี้ (วันที่กำลังจะจบ)
         *  - รันช่วงเช้ามืด (หลังเที่ยงคืน) → สรุปเมื่อวาน (วันที่เพิ่งจบ)
         * ทำให้ดึงข้อมูลถูกวันเสมอ ไม่ว่า WorkManager จะเด้งก่อนหรือหลังเที่ยงคืน
         */
        fun targetDayKey(): String {
            val now = Calendar.getInstance()
            val cal = now.clone() as Calendar
            if (now.get(Calendar.HOUR_OF_DAY) < 12) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            return Util.dayKey(cal.timeInMillis)
        }

        /** สร้างข้อความสรุปของวัน dayKey แล้วส่งไป Telegram (ใช้ทั้งงานอัตโนมัติและปุ่มทดสอบ) */
        suspend fun sendSummary(context: Context, dayKey: String): Boolean {
            val prefs = Prefs(context)
            if (!prefs.isConfigured) return false

            val db = AppDatabase.get(context)
            val stats = db.sessionDao().summarizeDay(dayKey)
            val telegram = TelegramClient(prefs.botToken, prefs.chatId)

            val message = buildString {
                append("🌙 <b>สรุปการใช้แอปประจำวัน</b>\n")
                append("📅 $dayKey\n\n")
                if (stats.isEmpty()) {
                    append("ไม่มีการใช้งานที่บันทึกไว้ในวันนี้")
                } else {
                    var totalMs = 0L
                    var totalOpens = 0
                    stats.forEachIndexed { i, s ->
                        totalMs += s.totalMs
                        totalOpens += s.openCount
                        append("${i + 1}. <b><code>${Util.esc(s.appLabel)}</code></b>\n")
                        append("    • เปิด ${s.openCount} ครั้ง\n")
                        append("    • รวม ${Util.humanDuration(s.totalMs)}\n")
                    }
                    append("\n━━━━━━━━━━\n")
                    append("รวมทั้งหมด: เปิด $totalOpens ครั้ง, ${Util.humanDuration(totalMs)}")
                }
            }
            return telegram.send(message)
        }

        /** ตั้งให้รันทุกวันเวลาประมาณเที่ยงคืน (00:05) */
        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val initialDelay = next.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
