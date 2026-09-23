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

    /** ส่งข้อความ (เรียกจาก background thread เท่านั้น — บล็อกจนได้ผล) */
    fun send(text: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        return try {
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
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Telegram error ${resp.code}: ${resp.body?.string()}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "ส่ง Telegram ล้มเหลว", e)
            false
        }
    }

    companion object {
        private const val TAG = "TelegramClient"
    }
}
