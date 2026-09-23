package com.family.usagemonitor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/** ทิศทางของสายโทร */
enum class CallType { INCOMING, OUTGOING, MISSED }

/**
 * จับสถานะการโทรของเครื่องโดยตรง (ไม่ผ่านหน้าจอ) → ได้เวลาคุยจริงแม้จอดับ
 *  - RINGING  = มีสายเรียกเข้า
 *  - OFFHOOK  = กำลังคุยสาย (รับแล้ว / โทรออก)
 *  - IDLE     = วางสาย
 */
class CallTracker(
    private val context: Context,
    /** callback: (ประเภทสาย, ระยะเวลาคุยเป็น ms) — MISSED จะได้ 0 */
    private val onCallFinished: (CallType, Long) -> Unit
) {
    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var callStart = 0L
    private var sawRinging = false

    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: Any? = null   // TelephonyCallback (API 31+)

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onState(state)
            }
            modernCallback = cb
            tm.registerTelephonyCallback(context.mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modernCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
    }

    private fun onState(state: Int) {
        val now = System.currentTimeMillis()
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                sawRinging = true
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (callStart == 0L) callStart = now
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                when {
                    callStart > 0L -> {
                        val duration = now - callStart
                        val type = if (sawRinging) CallType.INCOMING else CallType.OUTGOING
                        onCallFinished(type, duration)
                    }
                    sawRinging -> {
                        // เรียกเข้าแต่ไม่ได้รับ
                        onCallFinished(CallType.MISSED, 0L)
                    }
                }
                callStart = 0L
                sawRinging = false
            }
        }
    }
}
