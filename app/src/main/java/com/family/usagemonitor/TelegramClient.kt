package com.family.usagemonitor

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** ยิงข้อความไปยัง Telegram Bot ผ่าน HTTP API */
class TelegramClient(
    private val botToken: String,
    private val chatId: String
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * ส่งข้อความ (เรียกจาก background thread เท่านั้น — บล็อกจนได้ผล)
     * - ต่อคิวทีละข้อความ + เว้นระยะห่างขั้นต่ำ กัน Telegram บล็อก (429)
     * - ถ้าโดน 429 จะรอตามเวลาที่บอกแล้วส่งซ้ำ → ข้อความไม่หาย
     */
    fun send(text: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        // ล็อกระดับ global ให้ทุกข้อความส่งทีละอันตามลำดับ
        synchronized(sendLock) {
            spaceOutFromLastSend()
            val ok = sendWithRetry(text)
            lastSendAt = System.currentTimeMillis()
            return ok
        }
    }

    /** เว้นระยะห่างจากข้อความก่อนหน้าอย่างน้อย MIN_INTERVAL_MS */
    private fun spaceOutFromLastSend() {
        val since = System.currentTimeMillis() - lastSendAt
        if (since in 0 until MIN_INTERVAL_MS) {
            try {
                Thread.sleep(MIN_INTERVAL_MS - since)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun sendWithRetry(text: String): Boolean {
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "true")
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendMessage")
            .post(body)
            .build()

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                http.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> return true
                        // โดนจำกัดความถี่ → รอตามที่ Telegram บอกแล้วลองใหม่
                        resp.code == 429 -> {
                            val wait = parseRetryAfter(resp.body?.string())
                            Log.w(TAG, "Telegram 429 รอ ${wait}ms แล้วส่งซ้ำ (ครั้งที่ ${attempt + 1})")
                            Thread.sleep(wait)
                        }
                        else -> {
                            Log.w(TAG, "Telegram error ${resp.code}: ${resp.body?.string()}")
                            return false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ส่ง Telegram ล้มเหลว (ครั้งที่ ${attempt + 1})", e)
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                }
            }
        }
        return false
    }

    /** อ่าน retry_after (วินาที) จาก body ถ้าไม่มีใช้ค่า default */
    private fun parseRetryAfter(body: String?): Long {
        if (body == null) return DEFAULT_RETRY_MS
        val sec = Regex("\"retry_after\"\\s*:\\s*(\\d+)").find(body)
            ?.groupValues?.get(1)?.toLongOrNull()
        return if (sec != null) (sec * 1000 + 500) else DEFAULT_RETRY_MS
    }

    companion object {
        private const val TAG = "TelegramClient"
        private const val MIN_INTERVAL_MS = 1100L   // เว้นระยะห่างขั้นต่ำระหว่างข้อความ
        private const val DEFAULT_RETRY_MS = 2000L
        private const val MAX_ATTEMPTS = 4

        // ล็อก + เวลาส่งล่าสุด แชร์ทุก instance → ส่งทีละข้อความทั้งแอป
        private val sendLock = Any()

        @Volatile
        private var lastSendAt = 0L
    }
}
