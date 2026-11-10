package com.example.expensetracker.lab

import com.example.expensetracker.net.LabCommandResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabCommandParserTest {

    private val sessionId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun response(
        command: String?,
        parameters: Map<String, String> = emptyMap(),
        issuedAtUtc: String? = "2026-10-14T09:00:00Z",
    ) = LabCommandResponse(command = command, issuedAtUtc = issuedAtUtc, parameters = parameters)

    @Test
    fun `every allowlisted action is accepted`() {
        LabCommand.entries.forEach { expected ->
            val decision = LabCommandParser.validate(response(expected.wireName), sessionId)
            assertTrue("$expected should be allowed", decision is CommandDecision.Accepted)
            assertEquals(expected, (decision as CommandDecision.Accepted).command)
        }
        assertEquals(4, LabCommand.entries.size)
    }

    @Test
    fun `unknown commands are rejected with the allowlist in the reason`() {
        val hostile = listOf(
            "SHELL",
            "EXEC ./payload.sh",
            "rm -rf /",
            "READ_SMS",
            "UPLOAD_CONTACTS",
            "INSTALL_APK",
            "show_lab_status",
            "",
            "   ",
            null,
        )

        hostile.forEach { wireName ->
            val decision = LabCommandParser.validate(response(wireName), sessionId)
            assertTrue("'$wireName' must not be accepted", decision is CommandDecision.Rejected)
            val reason = (decision as CommandDecision.Rejected).reason
            assertTrue(reason, reason.contains("no command") || reason.contains("Unsupported command"))
        }
    }

    @Test
    fun `commands carrying parameters are rejected even when the name is allowlisted`() {
        listOf(
            mapOf("target" to "/sdcard"),
            mapOf("command" to "id"),
            mapOf("path" to "../../etc/passwd", "exe" to "true"),
        ).forEach { parameters ->
            val decision = LabCommandParser.validate(
                response(LabCommand.SHOW_LAB_STATUS.wireName, parameters),
                sessionId,
            )
            assertTrue(decision is CommandDecision.Rejected)
            assertTrue((decision as CommandDecision.Rejected).reason.contains("parameter"))
        }
    }

    @Test
    fun `an undated command is rejected`() {
        listOf(null, "").forEach { stamp ->
            val decision = LabCommandParser.validate(
                response(LabCommand.WRITE_TEST_MARKER.wireName, issuedAtUtc = stamp),
                sessionId,
            )
            assertTrue(decision is CommandDecision.Rejected)
            assertTrue((decision as CommandDecision.Rejected).reason.contains("timestamp"))
        }
    }

    @Test
    fun `a command with no active session is rejected`() {
        val decision = LabCommandParser.validate(
            response(LabCommand.CREATE_SYNTHETIC_EVENT.wireName),
            expectedSessionId = "  ",
        )
        assertTrue(decision is CommandDecision.Rejected)
        assertTrue((decision as CommandDecision.Rejected).reason.contains("No lab session"))
    }

    @Test
    fun `the allowlist exposes only wire names and nothing executable`() {
        assertEquals(
            setOf("SHOW_LAB_STATUS", "CREATE_SYNTHETIC_EVENT", "WRITE_TEST_MARKER", "END_LAB_SESSION"),
            LabCommand.allowedWireNames,
        )
        LabCommand.entries.forEach { command ->
            assertTrue(command.description.isNotBlank())
            assertFalseLikeCode(command.wireName)
            assertFalseLikeCode(command.description)
        }
        assertNull(LabCommand.fromWireName("DROP_TABLE"))
        // Matching is exact: a near miss never resolves to an action.
        assertNull(LabCommand.fromWireName("show_lab_status"))
    }

    private fun assertFalseLikeCode(value: String) {
        val markers = listOf("/", "\\", ";", "&&", "|", "$", "`", "http", ".sh", ".apk", "rm ", "cat ")
        markers.forEach { assertTrue("'$value' looks executable ($it)", !value.contains(it)) }
    }
}
