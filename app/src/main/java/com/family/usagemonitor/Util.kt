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

    /** หนีอักขระพิเศษ HTML ของ Telegram (& < >) กันข้อความเพี้ยน */
    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    // จำผลว่าแต่ละแพ็กเกจ "เปิดเองได้ไหม" กันเช็กซ้ำบ่อยๆ
    private val launchableCache = HashMap<String, Boolean>()

    /**
     * แอปนี้เป็นแอปที่ผู้ใช้เปิดเองได้จริงไหม (มีไอคอนใน launcher)
     * เช็กทีละแพ็กเกจ (แบบ v1.6 ที่พิสูจน์แล้วว่าเสถียร)
     * ⚠️ ถ้าเช็กไม่ได้ (exception) จะปล่อยผ่าน — กันเผลอกรองแอปจริงทิ้ง
     */
    fun isLaunchable(context: Context, pkg: String): Boolean {
        launchableCache[pkg]?.let { return it }
        val result = try {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) {
            true
        }
        launchableCache[pkg] = result
        return result
    }

    /** ดึงชื่อแอปที่อ่านง่าย จาก package name */
    fun appLabel(context: Context, pkg: String): String {
        // 1) ลองอ่านชื่อจริงจากระบบ (ได้ผลถ้ามีสิทธิ์ QUERY_ALL_PACKAGES)
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString()
            if (label.isNotBlank() && label != pkg) return label
        } catch (_: Exception) {
        }
        // 2) ระบบอ่านไม่ได้ → ใช้ตารางเทียบแอปยอดนิยม
        KNOWN_APPS[pkg]?.let { return it }
        // 3) สุดท้ายจริงๆ คืนชื่อแพ็กเกจ
        return pkg
    }

    /** ตารางเทียบชื่อแอปยอดนิยม (เผื่อระบบอ่านชื่อไม่ได้) */
    private val KNOWN_APPS = mapOf(
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.facebook.katana" to "Facebook",
        "com.facebook.lite" to "Facebook Lite",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "jp.naver.line.android" to "LINE",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.gm" to "Gmail",
        "com.android.chrome" to "Chrome",
        "com.whatsapp" to "WhatsApp",
        "com.twitter.android" to "X",
        "com.x.android" to "X",
        "com.shopee.th" to "Shopee",
        "com.lazada.android" to "Lazada",
        "com.linecorp.linetv" to "LINE TV",
        "com.netflix.mediaclient" to "Netflix",
        "com.spotify.music" to "Spotify",
        "com.google.android.googlequicksearchbox" to "Google",
        "com.android.vending" to "Play Store"
    )

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
