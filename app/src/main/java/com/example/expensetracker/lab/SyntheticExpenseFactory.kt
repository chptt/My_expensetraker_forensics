package com.example.expensetracker.lab

import java.util.UUID

/**
 * A synthetic expense produced by the lab module. This type is deliberately separate from
 * [com.example.expensetracker.data.model.Expense]: the payload builder only accepts synthetic
 * records, so a real user row cannot be handed to it by construction.
 */
data class SyntheticExpenseRecord(
    val syntheticEventId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val categoryKey: String,
    val description: String,
    val occurredAtUtcMillis: Long,
    val synthetic: Boolean = true,
)

/**
 * Behaviour B. Values are hard-coded fixtures from the lab script - no user data, no randomness in
 * the amounts/categories, and nothing is ever inserted into the expenses table.
 */
object SyntheticExpenseFactory {

    private data class Fixture(
        val amountMinor: Long,
        val categoryKey: String,
        val description: String,
    )

    private val FIXTURES = listOf(
        Fixture(12_500L, "FOOD", "Lab fixture - canteen counter A"),
        Fixture(4_500L, "TRANSPORT", "Lab fixture - metro token"),
        Fixture(99_900L, "SHOPPING", "Lab fixture - stationery store"),
        Fixture(150_000L, "EDUCATION", "Lab fixture - course material"),
        Fixture(27_500L, "OTHER", "Lab fixture - misc counter"),
        Fixture(63_000L, "FOOD", "Lab fixture - cafeteria tray"),
        Fixture(3_200L, "TRANSPORT", "Lab fixture - cycle rental"),
        Fixture(210_000L, "SHOPPING", "Lab fixture - electronics kiosk"),
    )

    const val SYNTHETIC_DESCRIPTION_PREFIX = "Lab fixture - "

    /**
     * The one event submitted by the *Run Security Test* button. Its id and description are fixed so
     * the Android rows, the JSONL log and the Flask access log all carry the same searchable token.
     * The wire schema has no event-type field, so the type and purpose travel inside the description.
     */
    const val SECURITY_TEST_FIXTURE_ID = "syn-fixture-demo-001"
    const val SECURITY_TEST_EVENT_TYPE = "SYNTHETIC_TRANSACTION_VIEWED"
    const val SECURITY_TEST_PURPOSE = "MOBILE_FORENSICS_TEST"
    const val SECURITY_TEST_AMOUNT_MINOR = 10_000L
    const val SECURITY_TEST_CATEGORY = "OTHER"

    fun securityTestFixture(occurredAtUtcMillis: Long): SyntheticExpenseRecord =
        SyntheticExpenseRecord(
            syntheticEventId = SECURITY_TEST_FIXTURE_ID,
            amountMinor = SECURITY_TEST_AMOUNT_MINOR,
            currencyCode = CURRENCY,
            categoryKey = SECURITY_TEST_CATEGORY,
            description = "$SYNTHETIC_DESCRIPTION_PREFIX$SECURITY_TEST_EVENT_TYPE purpose=$SECURITY_TEST_PURPOSE",
            occurredAtUtcMillis = occurredAtUtcMillis,
        )

    fun generate(
        sessionStartMillis: Long,
        count: Int = DEFAULT_COUNT,
        randomId: () -> String = { UUID.randomUUID().toString() },
    ): List<SyntheticExpenseRecord> {
        val requested = count.coerceIn(1, MAX_COUNT)
        return (0 until requested).map { index ->
            val fixture = FIXTURES[index % FIXTURES.size]
            SyntheticExpenseRecord(
                syntheticEventId = "syn-${randomId()}",
                amountMinor = fixture.amountMinor,
                currencyCode = CURRENCY,
                categoryKey = fixture.categoryKey,
                description = fixture.description,
                occurredAtUtcMillis = sessionStartMillis + index * STAGGER_MILLIS,
                synthetic = true,
            )
        }
    }

    /**
     * Guards the one place a mistake could leak real data: only records whose description carries
     * the fixture prefix and whose id carries the fixture namespace are accepted.
     */
    fun isSynthetic(record: SyntheticExpenseRecord): Boolean =
        record.synthetic &&
            record.syntheticEventId.startsWith(SYNTHETIC_ID_PREFIX) &&
            isSyntheticDescription(record.description)

    fun isSyntheticDescription(description: String): Boolean =
        description.startsWith(SYNTHETIC_DESCRIPTION_PREFIX)

    const val DEFAULT_COUNT = 5
    const val MAX_COUNT = 12
    const val CURRENCY = "INR"
    private const val SYNTHETIC_ID_PREFIX = "syn-"
    private const val STAGGER_MILLIS = 60_000L
}
