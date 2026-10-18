package com.nevoit.cirro.detail

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun relativeAddedDay(
    addedAtMs: Long,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (addedAtMs <= 0) return ""
    val addedDay = Instant.ofEpochMilli(addedAtMs).atZone(zone).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(addedDay, today).coerceAtLeast(0)) {
        0L -> "今天"
        1L -> "昨天"
        else -> "${days}天前"
    }
}

/** 「XXXX年X月X日」。时间戳无效给 null，调用方整段省略。详情页与艺人页共用。 */
internal fun formatDate(ms: Long): String? {
    if (ms <= 0) return null
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}
