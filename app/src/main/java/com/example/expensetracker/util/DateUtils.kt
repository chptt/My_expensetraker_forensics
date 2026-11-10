package com.example.expensetracker.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Date helpers for the month selector and the expense date field. */
object DateUtils {

    private val displayFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val compactFormatter = DateTimeFormatter.ofPattern("dd MMM")
    private val monthTitleFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val isoUtcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))

    fun formatTimestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        displayFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun formatCompact(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        compactFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun utcIso(epochMillis: Long): String = isoUtcFormatter.format(Instant.ofEpochMilli(epochMillis))

    fun monthTitle(yearMonth: YearMonthKey): String =
        monthTitleFormatter.format(LocalDate.of(yearMonth.year, yearMonth.month, 1))

    /** Inclusive epoch-millisecond bounds for a calendar month in the given zone. */
    fun monthBounds(yearMonth: YearMonthKey, zone: ZoneId = ZoneId.systemDefault()): LongRange {
        val firstOfMonth = LocalDate.of(yearMonth.year, yearMonth.month, 1).atStartOfDay(zone)
        val start = firstOfMonth.toInstant().toEpochMilli()
        val endExclusive = firstOfMonth.plusMonths(1).toInstant().toEpochMilli()
        return start until endExclusive
    }

    fun todayZoneMonth(zone: ZoneId = ZoneId.systemDefault()): YearMonthKey {
        val now = LocalDate.now(zone)
        return YearMonthKey(now.year, now.monthValue)
    }

    fun epochMillisOfDay(year: Int, month: Int, day: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}

data class YearMonthKey(val year: Int, val month: Int) : Comparable<YearMonthKey> {
    override fun compareTo(other: YearMonthKey): Int =
        compareValuesBy(this, other, { it.year }, { it.month })

    fun shifted(months: Int): YearMonthKey {
        val zeroBased = year * 12 + (month - 1) + months
        return YearMonthKey(Math.floorDiv(zeroBased, 12), Math.floorMod(zeroBased, 12) + 1)
    }

    fun contains(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        epochMillis in DateUtils.monthBounds(this, zone)
}
