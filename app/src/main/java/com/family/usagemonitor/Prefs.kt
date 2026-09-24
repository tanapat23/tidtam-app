package com.family.usagemonitor

import android.content.Context

/** เก็บค่าตั้งค่าเล็กๆ น้อยๆ (Telegram token / chat id / เปิด-ปิดแจ้งเตือนตอนออกแอป) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var botToken: String
        get() = sp.getString("bot_token", "") ?: ""
        set(v) = sp.edit().putString("bot_token", v.trim()).apply()

    var chatId: String
        get() = sp.getString("chat_id", "") ?: ""
        set(v) = sp.edit().putString("chat_id", v.trim()).apply()

    /** แจ้งเตือนตอนออกจากแอป (พร้อมจำนวนนาที) ด้วยไหม */
    var notifyOnExit: Boolean
        get() = sp.getBoolean("notify_exit", true)
        set(v) = sp.edit().putBoolean("notify_exit", v).apply()

    /** ผู้ใช้สั่งให้ติดตามอยู่หรือไม่ (ไว้เปิด service ให้เองเมื่อเปิดแอป/รีบูต) */
    var trackingEnabled: Boolean
        get() = sp.getBoolean("tracking_enabled", false)
        set(v) = sp.edit().putBoolean("tracking_enabled", v).apply()

    /** ตัดเซสชันที่สั้นกว่า X มิลลิวินาที (กันสแปมตอนสลับแอปผ่านๆ) */
    val minSessionMs: Long get() = 3_000L

    val isConfigured: Boolean get() = botToken.isNotEmpty() && chatId.isNotEmpty()
}
