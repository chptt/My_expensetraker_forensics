package com.example.expensetracker.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LabPayloadBuilderTest {

    private val sessionStart = 1_760_000_000_000L
    private val sessionId = "11111111-2222-3333-4444-555555555555"

    private fun fixtures(count: Int = 3) =
        SyntheticExpenseFactory.generate(sessionStart, count)

    @Test
    fun `a fixture batch becomes an ingest request stamped as synthetic`() {
        val request = LabPayloadBuilder.build(sessionId, fixtures(), appVersion = "1.0.0")

        assertEquals(sessionId, request.sessionId)
        assertEquals("1.0.0", request.appVersion)
        assertTrue(request.preparedAtUtc.endsWith("Z"))
        assertEquals(3, request.events.size)
        request.events.forEach { event ->
            assertTrue(event.synthetic)
            assertTrue(event.eventId.startsWith("syn-"))
            assertTrue(event.description.startsWith("Lab fixture - "))
            assertTrue(event.occurredAtUtc.endsWith("Z"))
        }
    }

    @Test
    fun `a blank session id is refused`() {
        assertViolation("session id") { LabPayloadBuilder.build("  ", fixtures(), "1.0.0") }
    }

    @Test
    fun `an empty batch is refused`() {
        assertViolation("empty") { LabPayloadBuilder.build(sessionId, emptyList(), "1.0.0") }
    }

    @Test
    fun `records without the fixture markers are refused`() {
        val tampered = fixtures().mapIndexed { index, record ->
            if (index == 1) record.copy(description = "Actual rent payment") else record
        }

        assertViolation("not lab fixtures") { LabPayloadBuilder.build(sessionId, tampered, "1.0.0") }
    }

    @Test
    fun `a record that simply claims to be synthetic is still refused`() {
        val pretend = fixtures(1).single().copy(synthetic = false)
        assertViolation("not lab fixtures") { LabPayloadBuilder.build(sessionId, listOf(pretend), "1.0.0") }
    }

    @Test
    fun `a real user expense cannot be represented as a lab record at all`() {
        // The builder only accepts SyntheticExpenseRecord; a stored Expense has no such shape, so
        // the only way to leak one is to hand-write a fixture-looking record. That is what the
        // marker check above blocks.
        val built = LabPayloadBuilder.build(sessionId, fixtures(1), "1.0.0")
        assertEquals(1, built.events.size)
        assertTrue(built.events.single().description.startsWith(SyntheticExpenseFactory.SYNTHETIC_DESCRIPTION_PREFIX))
    }

    private fun assertViolation(containsInMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected SyntheticPayloadViolation mentioning '$containsInMessage'")
        } catch (violation: LabPayloadBuilder.SyntheticPayloadViolation) {
            val message = violation.message.orEmpty()
            assertTrue(
                "Unexpected message: $message",
                message.contains(containsInMessage, ignoreCase = true),
            )
        }
    }
}
