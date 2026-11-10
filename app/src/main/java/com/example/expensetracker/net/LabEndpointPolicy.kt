package com.example.expensetracker.net

import java.net.URI
import java.util.Locale

/**
 * Refuses endpoint URLs that do not belong to the isolated lab. Cleartext http is only accepted for
 * hosts explicitly listed by the build (the emulator loopback addresses); anything else must be TLS.
 */
object LabEndpointPolicy {

    sealed interface Decision {
        data class Allowed(val normalized: String) : Decision
        data class Rejected(val reason: String) : Decision
    }

    fun validate(rawUrl: String, allowedCleartextHosts: Set<String>): Decision {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return Decision.Rejected("No endpoint configured")

        val uri = try {
            URI(trimmed)
        } catch (error: Exception) {
            return Decision.Rejected("Malformed URL")
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
            ?: return Decision.Rejected("URL must include a scheme")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return Decision.Rejected("URL must include a host")
        val port = uri.port

        return when (scheme) {
            "https" -> Decision.Allowed(normalize(uri))
            "http" ->
                if (host.lowercase(Locale.US) in allowedCleartextHosts) Decision.Allowed(normalize(uri))
                else Decision.Rejected(
                    "Cleartext http is only permitted for lab hosts ${allowedCleartextHosts.joinToString()}"
                )

            else -> Decision.Rejected("Unsupported scheme '$scheme'")
        }
    }

    private fun normalize(uri: URI): String {
        val portPart = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path.let { if (it.isEmpty() || it == "/") "/" else if (it.endsWith("/")) it else "$it/" }
        return "${uri.scheme.lowercase(Locale.US)}://${uri.host.lowercase(Locale.US)}$portPart$path"
    }
}
