package com.nevoit.cirro.detail

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionDatesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 13)

    private fun timestamp(day: LocalDate, hour: Int = 0): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun daysFollowCalendarDatesRatherThanElapsedHours() {
        assertEquals("今天", relativeAddedDay(timestamp(today), today, zone))
        assertEquals("昨天", relativeAddedDay(timestamp(today.minusDays(1), 23), today, zone))
        assertEquals("2天前", relativeAddedDay(timestamp(today.minusDays(2), 23), today, zone))
    }

    @Test
    fun unknownTimesDoNotBecomeTodayAndFutureTimesNeverBecomeNegativeDays() {
        assertEquals("", relativeAddedDay(0, today, zone))
        assertEquals("", relativeAddedDay(-1, today, zone))
        assertEquals("今天", relativeAddedDay(timestamp(today.plusDays(1)), today, zone))
    }

    @Test
    fun usesTheLocalDateEvenWhenUtcIsStillThePreviousDay() {
        assertEquals("今天", relativeAddedDay(timestamp(today), today, zone))
        assertEquals("昨天", relativeAddedDay(timestamp(today), today, ZoneId.of("UTC")))
    }

    @Test
    fun daylightSavingChangesDoNotChangeDayCounts() {
        val newYork = ZoneId.of("America/New_York")
        val afterTransition = LocalDate.of(2026, 3, 9)
        val added = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, newYork).toInstant().toEpochMilli()
        assertEquals("2天前", relativeAddedDay(added, afterTransition, newYork))
    }
}
