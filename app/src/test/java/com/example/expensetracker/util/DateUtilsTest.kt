package com.example.expensetracker.util

import java.time.Month
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DateUtilsTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `month bounds cover exactly one calendar month`() {
        val bounds = DateUtils.monthBounds(YearMonthKey(2026, 2), utc)

        assertEquals(DateUtils.epochMillisOfDay(2026, 2, 1, utc), bounds.first)
        assertEquals("2026-02-01T00:00:00Z", DateUtils.utcIso(bounds.first))
        // February 2026 has 28 days, so the exclusive end is 1 March.
        assertEquals("2026-03-01T00:00:00Z", DateUtils.utcIso(bounds.last + 1))
        assertEquals(28, (bounds.last + 1 - bounds.first) / MILLIS_PER_DAY)
    }

    @Test
    fun `december bounds roll into the next year`() {
        val bounds = DateUtils.monthBounds(YearMonthKey(2026, 12), utc)
        assertEquals("2026-12-01T00:00:00Z", DateUtils.utcIso(bounds.first))
        assertEquals("2027-01-01T00:00:00Z", DateUtils.utcIso(bounds.last + 1))
    }

    @Test
    fun `timestamps format in the supplied zone`() {
        val millis = DateUtils.epochMillisOfDay(2026, 10, 14, utc)
        assertEquals("14 Oct 2026", DateUtils.formatTimestamp(millis, utc))
        assertEquals("14 Oct", DateUtils.formatCompact(millis, utc))
        assertEquals("14 Oct 2026", DateUtils.formatTimestamp(millis, ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun `month titles are human readable`() {
        assertEquals("October 2026", DateUtils.monthTitle(YearMonthKey(2026, 10)))
        assertEquals("January 2027", DateUtils.monthTitle(YearMonthKey(2027, 1)))
    }

    @Test
    fun `shifting crosses year boundaries in both directions`() {
        assertEquals(YearMonthKey(2027, 1), YearMonthKey(2026, 12).shifted(1))
        assertEquals(YearMonthKey(2026, 12), YearMonthKey(2027, 1).shifted(-1))
        assertEquals(YearMonthKey(2025, 11), YearMonthKey(2026, 12).shifted(-13))
        assertEquals(YearMonthKey(2026, 10), YearMonthKey(2026, 10).shifted(0))
    }

    @Test
    fun `keys order chronologically`() {
        assertTrue(YearMonthKey(2026, 12) > YearMonthKey(2026, 11))
        assertTrue(YearMonthKey(2027, 1) > YearMonthKey(2026, 12))
        assertFalse(YearMonthKey(2026, 1) > YearMonthKey(2026, 2))
        assertEquals(
            listOf(YearMonthKey(2025, 12), YearMonthKey(2026, 1), YearMonthKey(2026, 2)),
            listOf(YearMonthKey(2026, 1), YearMonthKey(2026, 2), YearMonthKey(2025, 12)).sorted(),
        )
    }

    @Test
    fun `contains only accepts instants inside the month`() {
        val october = YearMonthKey(2026, Month.OCTOBER.value)
        assertTrue(october.contains(DateUtils.epochMillisOfDay(2026, 10, 1, utc), utc))
        assertTrue(october.contains(DateUtils.epochMillisOfDay(2026, 10, 31, utc), utc))
        assertFalse(october.contains(DateUtils.epochMillisOfDay(2026, 11, 1, utc), utc))
    }

    @Test
    fun `utc iso timestamps are sortable text with an explicit zone marker`() {
        val earlier = DateUtils.utcIso(DateUtils.epochMillisOfDay(2026, 10, 1, utc))
        val later = DateUtils.utcIso(DateUtils.epochMillisOfDay(2026, 10, 2, utc))
        assertEquals("2026-10-01T00:00:00Z", earlier)
        assertTrue(earlier < later)
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
