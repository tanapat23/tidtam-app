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
 * งานที่รันทุกเที่ยงคืน: สรุปการใช้แอป "ของวันที่เพิ่งจบไป" แล้วส่งไป Telegram
 */
class DailySummaryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.isConfigured) return Result.success()

        val db = AppDatabase.get(applicationContext)

        // วันที่เพิ่งจบไป = วันนี้ - 1 วัน (เพราะรันตอนหลังเที่ยงคืน)
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val dayKey = Util.dayKey(cal.timeInMillis)

        val stats = db.sessionDao().summarizeDay(dayKey)
        val telegram = TelegramClient(prefs.botToken, prefs.chatId)

        val message = buildString {
            append("🌙 <b>สรุปการใช้แอปประจำวัน</b>\n")
            append("📅 $dayKey\n\n")
            if (stats.isEmpty()) {
                append("วันนี้ไม่มีการใช้งานที่บันทึกไว้")
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
        telegram.send(message)

        // ลบข้อมูลเก่ากว่า 30 วัน กันฐานข้อมูลบวม
        val old = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }
        db.sessionDao().deleteOlderThan(Util.dayKey(old.timeInMillis))

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_summary"

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
