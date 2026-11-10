package com.example.expensetracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parses whole and decimal amounts into minor units`() {
        assertEquals(25_000L, Money.parseToMinor("250"))
        assertEquals(24_950L, Money.parseToMinor("249.50"))
        assertEquals(24_950L, Money.parseToMinor("249.5"))
        assertEquals(5L, Money.parseToMinor("0.05"))
    }

    @Test
    fun `ignores currency symbols spacing and grouping commas`() {
        assertEquals(12_500L, Money.parseToMinor(" ₹125.00 "))
        assertEquals(125_000L, Money.parseToMinor("1,250"))
    }

    @Test
    fun `rejects amounts that are not usable`() {
        assertNull(Money.parseToMinor(""))
        assertNull(Money.parseToMinor("   "))
        assertNull(Money.parseToMinor("-50"))
        assertNull(Money.parseToMinor("0"))
        assertNull(Money.parseToMinor("0.00"))
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor("12.345"))
        assertNull(Money.parseToMinor("12..5"))
        assertNull(Money.parseToMinor("99999999999999"))
    }

    @Test
    fun `formats minor units with Indian digit grouping`() {
        assertEquals("₹0.00", Money.format(0L))
        assertEquals("₹125.00", Money.format(12_500L))
        assertEquals("₹249.50", Money.format(24_950L))
        assertEquals("₹1,25,000.00", Money.format(12_500_000L))
        assertEquals("₹1,00,00,000.00", Money.format(1_000_000_000L))
    }

    @Test
    fun `parse and format round trip preserves the stored minor units`() {
        val inputs = listOf("1", "1.5", "99.99", "1000", "123456.78", "0.05")
        inputs.forEach { raw ->
            val minor = requireNotNull(Money.parseToMinor(raw)) { "should parse $raw" }
            val reformatted = Money.format(minor, withSymbol = false)
            assertEquals(minor, Money.parseToMinor(reformatted))
        }
    }
}
