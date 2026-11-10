package com.example.expensetracker.util

/**
 * Money is handled in minor units (paise). Every parse/format path goes through here so no float
 * ever touches a stored amount.
 */
object Money {

    const val MAX_AMOUNT_MINOR = 100_000_000_00L // 10,000,000,000 major units guard

    /** Returns amount in minor units, or null when the text is not a valid positive amount. */
    fun parseToMinor(raw: String): Long? {
        val trimmed = raw.trim().replace(" ", "").replace(",", "").removePrefix("₹")
        if (trimmed.isEmpty() || trimmed.startsWith("-")) return null
        val match = AMOUNT_PATTERN.matchEntire(trimmed) ?: return null
        val whole = match.groupValues[1]
        val fraction = match.groupValues[2].padEnd(2, '0').take(2)
        val minor = whole.toLongOrNull()?.times(100)?.plus(fraction.toLong()) ?: return null
        if (minor <= 0L) return null
        if (minor > MAX_AMOUNT_MINOR) return null
        return minor
    }

    fun format(minor: Long, withSymbol: Boolean = true): String {
        val safe = if (minor < 0) 0 else minor
        val whole = safe / 100
        val fraction = safe % 100
        val grouped = groupThousands(whole)
        val sign = if (withSymbol) "₹" else ""
        return "$sign$grouped.${fraction.toString().padStart(2, '0')}"
    }

    private fun groupThousands(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val groups = mutableListOf<String>()
        var index = head.length
        while (index > 2) {
            groups.add(head.substring(index - 2, index))
            index -= 2
        }
        if (index > 0) groups.add(head.substring(0, index))
        return groups.reversed().joinToString(",") + "," + tail
    }

    private val AMOUNT_PATTERN = Regex("""(\d{1,12})(?:\.(\d{1,2}))?""")
}
