package com.family.usagemonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 1 แถว = 1 ครั้งที่เปิดแอปหนึ่งขึ้นมาอยู่หน้าจอ จนออกจากแอปนั้น
 */
@Entity(tableName = "app_sessions")
data class AppSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val startTime: Long,      // เวลาเข้า (epoch millis)
    val endTime: Long,        // เวลาออก (epoch millis)
    val durationMs: Long,     // ระยะเวลาที่อยู่หน้าจอ
    val dayKey: String        // "yyyy-MM-dd" ของวันที่เข้าแอป (ไว้ group ตอนสรุป)
)
