package com.example.expensetracker.lab

import com.example.expensetracker.net.LabCommandResponse

/**
 * Behaviour D. The only actions the app will ever perform on server instruction. Each one is a
 * local, reversible, no-argument test action - there is no code path that evaluates text coming
 * from the server.
 */
enum class LabCommand(val wireName: String, val description: String) {
    SHOW_LAB_STATUS("SHOW_LAB_STATUS", "Report current lab status into the local log"),
    CREATE_SYNTHETIC_EVENT("CREATE_SYNTHETIC_EVENT", "Append one synthetic event to the local log"),
    WRITE_TEST_MARKER("WRITE_TEST_MARKER", "Write a timestamped marker file in the lab directory"),
    END_LAB_SESSION("END_LAB_SESSION", "Close the current lab session"),
    ;

    companion object {
        val allowedWireNames: Set<String> = entries.map { it.wireName }.toSet()

        fun fromWireName(name: String?): LabCommand? = entries.firstOrNull { it.wireName == name }
    }
}

sealed interface CommandDecision {
    data class Accepted(val command: LabCommand) : CommandDecision
    data class Rejected(val reason: String) : CommandDecision
}

object LabCommandParser {

    /**
     * Validates a server command response. Unknown names, extra parameters and commands aimed at a
     * different session are all rejected here rather than being interpreted later.
     */
    fun validate(
        response: LabCommandResponse,
        expectedSessionId: String,
    ): CommandDecision {
        val name = response.command
        if (name.isNullOrBlank()) return CommandDecision.Rejected("Server returned no command")

        val command = LabCommand.fromWireName(name)
            ?: return CommandDecision.Rejected(
                "Unsupported command '$name'; allowlist is ${LabCommand.allowedWireNames.joinToString()}"
            )

        if (response.parameters.isNotEmpty()) {
            return CommandDecision.Rejected(
                "Command '$name' carried ${response.parameters.size} parameter(s); allowlisted actions take none"
            )
        }

        if (response.issuedAtUtc.isNullOrBlank()) {
            return CommandDecision.Rejected("Command '$name' has no issued_at_utc timestamp")
        }

        if (expectedSessionId.isBlank()) {
            return CommandDecision.Rejected("No lab session is active, refusing command '$name'")
        }

        return CommandDecision.Accepted(command)
    }
}
