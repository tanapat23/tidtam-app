package com.family.usagemonitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    fun dayKey(millis: Long): String = dayFmt.format(Date(millis))
    fun clock(millis: Long): String = timeFmt.format(Date(millis))

    /** แปลง ms เป็นข้อความไทย เช่น "1 ชม. 5 นาที" หรือ "45 วินาที" */
    fun humanDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "$h ชม. $m นาที"
            m > 0 -> "$m นาที $s วินาที"
            else -> "$s วินาที"
        }
    }

    /** ดึงชื่อแอปที่อ่านง่าย จาก package name */
    fun appLabel(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /**
     * รายชื่อแพ็กเกจที่ไม่ต้องนับ/ไม่ต้องแจ้ง:
     * ตัวแอปเราเอง, system UI, และ launcher (หน้าโฮม)
     */
    fun buildIgnoreSet(context: Context): Set<String> {
        val ignore = mutableSetOf(
            context.packageName,
            "com.android.systemui",
            "android"
        )
        // หา launcher ปัจจุบัน (หน้าโฮม) แล้วเพิ่มเข้า ignore
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        resolve?.activityInfo?.packageName?.let { ignore.add(it) }

        // แอปโทรศัพท์ (dialer) — ให้ CallTracker จับเวลาโทรแทน จะได้ไม่นับซ้ำ
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let { ignore.add(it) }
        } catch (_: Exception) {
        }
        return ignore
    }
}
