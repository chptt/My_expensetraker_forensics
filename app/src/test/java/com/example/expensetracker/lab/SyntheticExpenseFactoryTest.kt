package com.example.expensetracker.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticExpenseFactoryTest {

    private val sessionStart = 1_760_000_000_000L

    @Test
    fun `a batch is fixed fictional data with the lab fixture markers`() {
        val records = SyntheticExpenseFactory.generate(sessionStart, count = 4) { "fixed-id" }

        assertEquals(4, records.size)
        assertEquals(
            listOf(12_500L, 4_500L, 99_900L, 150_000L),
            records.map { it.amountMinor },
        )
        assertEquals(
            listOf("FOOD", "TRANSPORT", "SHOPPING", "EDUCATION"),
            records.map { it.categoryKey },
        )
        records.forEach { record ->
            assertEquals("syn-fixed-id", record.syntheticEventId)
            assertEquals("INR", record.currencyCode)
            assertTrue(record.description.startsWith(SyntheticExpenseFactory.SYNTHETIC_DESCRIPTION_PREFIX))
            assertTrue(record.synthetic)
        }
    }

    @Test
    fun `fixture descriptions never look like user notes`() {
        val descriptions = SyntheticExpenseFactory.generate(sessionStart, SyntheticExpenseFactory.MAX_COUNT)
            .map { it.description }
            .distinct()

        assertTrue(descriptions.isNotEmpty())
        descriptions.forEach { assertTrue(it.startsWith("Lab fixture - ")) }
    }

    @Test
    fun `count is clamped to the lab limits`() {
        assertEquals(1, SyntheticExpenseFactory.generate(sessionStart, count = 0).size)
        assertEquals(
            SyntheticExpenseFactory.MAX_COUNT,
            SyntheticExpenseFactory.generate(sessionStart, count = 5_000).size,
        )
        assertEquals(
            SyntheticExpenseFactory.DEFAULT_COUNT,
            SyntheticExpenseFactory.generate(sessionStart).size,
        )
    }

    @Test
    fun `event ids stay unique inside a batch`() {
        var counter = 0
        val records = SyntheticExpenseFactory.generate(sessionStart, count = 8) { "u${counter++}" }
        assertEquals(8, records.map { it.syntheticEventId }.distinct().size)
    }

    @Test
    fun `occurrence times are staggered forward from the session start`() {
        val records = SyntheticExpenseFactory.generate(sessionStart, count = 3)
        assertEquals(sessionStart, records[0].occurredAtUtcMillis)
        assertEquals(sessionStart + 60_000L, records[1].occurredAtUtcMillis)
        assertEquals(sessionStart + 120_000L, records[2].occurredAtUtcMillis)
    }

    @Test
    fun `the security test fixture is a fixed record that passes both guards`() {
        val record = SyntheticExpenseFactory.securityTestFixture(sessionStart)

        assertEquals(SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID, record.syntheticEventId)
        assertEquals(sessionStart, record.occurredAtUtcMillis)
        assertEquals(10_000L, record.amountMinor)
        assertEquals("INR", record.currencyCode)
        assertEquals("OTHER", record.categoryKey)
        assertTrue(record.synthetic)
        assertTrue(SyntheticExpenseFactory.isSynthetic(record))

        // The wire schema has no event-type field, so the identifiers ride along in the description.
        assertTrue(record.description.contains(SyntheticExpenseFactory.SECURITY_TEST_EVENT_TYPE))
        assertTrue(record.description.contains(SyntheticExpenseFactory.SECURITY_TEST_PURPOSE))
        assertTrue(record.description.startsWith(SyntheticExpenseFactory.SYNTHETIC_DESCRIPTION_PREFIX))
    }

    @Test
    fun `the security test fixture is identical on every call`() {
        assertEquals(
            SyntheticExpenseFactory.securityTestFixture(1L).copy(occurredAtUtcMillis = 0L),
            SyntheticExpenseFactory.securityTestFixture(999_999L).copy(occurredAtUtcMillis = 0L),
        )
    }

    @Test
    fun `the synthetic check refuses records that lost their markers`() {
        val real = SyntheticExpenseFactory.generate(sessionStart, count = 1).single()

        assertTrue(SyntheticExpenseFactory.isSynthetic(real))
        assertFalse(SyntheticExpenseFactory.isSynthetic(real.copy(synthetic = false)))
        assertFalse(SyntheticExpenseFactory.isSynthetic(real.copy(syntheticEventId = "evt-42")))
        assertFalse(SyntheticExpenseFactory.isSynthetic(real.copy(description = "Rent for my flat")))
        assertFalse(SyntheticExpenseFactory.isSyntheticDescription("Groceries at the market"))
        assertTrue(SyntheticExpenseFactory.isSyntheticDescription("Lab fixture - metro token"))
    }
}
