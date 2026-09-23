package com.family.usagemonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: AppSession): Long

    /** สรุปของวันหนึ่ง: แต่ละแอปเปิดกี่ครั้ง รวมเวลากี่ ms — เรียงจากใช้นานสุด */
    @Query(
        """
        SELECT appLabel AS appLabel,
               packageName AS packageName,
               COUNT(*) AS openCount,
               SUM(durationMs) AS totalMs
        FROM app_sessions
        WHERE dayKey = :dayKey
        GROUP BY packageName
        ORDER BY totalMs DESC
        """
    )
    suspend fun summarizeDay(dayKey: String): List<DailyAppStat>

    /** ลบข้อมูลเก่ากว่า dayKey ที่กำหนด (กันฐานข้อมูลบวม) */
    @Query("DELETE FROM app_sessions WHERE dayKey < :beforeDayKey")
    suspend fun deleteOlderThan(beforeDayKey: String)
}

/** ผลลัพธ์การสรุปต่อแอปต่อวัน */
data class DailyAppStat(
    val appLabel: String,
    val packageName: String,
    val openCount: Int,
    val totalMs: Long
)
