package com.example.expensetracker.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabEndpointPolicyTest {

    private val labHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")

    private fun allowed(raw: String): String =
        when (val decision = LabEndpointPolicy.validate(raw, labHosts)) {
            is LabEndpointPolicy.Decision.Allowed -> decision.normalized
            is LabEndpointPolicy.Decision.Rejected ->
                throw AssertionError("Expected '$raw' to be allowed but got: ${decision.reason}")
        }

    private fun rejected(raw: String): String =
        when (val decision = LabEndpointPolicy.validate(raw, labHosts)) {
            is LabEndpointPolicy.Decision.Rejected -> decision.reason
            is LabEndpointPolicy.Decision.Allowed ->
                throw AssertionError("Expected '$raw' to be rejected but got ${decision.normalized}")
        }

    @Test
    fun `the documented emulator loopback is accepted`() {
        assertEquals("http://10.0.2.2:5000/", allowed("http://10.0.2.2:5000"))
        assertEquals("http://10.0.2.2:5000/", allowed("http://10.0.2.2:5000/"))
        assertEquals("http://localhost:5000/", allowed("http://localhost:5000/"))
        assertEquals("http://127.0.0.1:8000/api/", allowed("http://127.0.0.1:8000/api"))
    }

    @Test
    fun `tls endpoints are always accepted and normalised`() {
        assertEquals("https://lab.example.org/", allowed("https://lab.example.org"))
        assertEquals("https://lab.example.org:8443/", allowed(" https://lab.example.org:8443/ "))
        assertEquals("https://lab.example.org/ingest/", allowed("https://lab.example.org/ingest"))
    }

    @Test
    fun `cleartext http to any other host is refused`() {
        listOf(
            "http://lab.example.org:5000/",
            "http://192.168.1.20:5000/",
            "http://attacker.invalid/",
        ).forEach { url ->
            val reason = rejected(url)
            assertTrue(reason, reason.contains("Cleartext"))
        }
    }

    @Test
    fun `non http schemes are refused`() {
        listOf("ftp://10.0.2.2:5000/", "file:///etc/passwd", "javascript:alert(1)", "10.0.2.2:5000").forEach {
            val reason = rejected(it)
            assertTrue(
                "$it -> $reason",
                reason.contains("scheme") || reason.contains("Malformed") || reason.contains("host"),
            )
        }
    }

    @Test
    fun `missing pieces are refused`() {
        assertTrue(rejected("").contains("No endpoint"))
        assertTrue(rejected("   ").contains("No endpoint"))
        // Either wording is a correct refusal: a URL with no host is either unparseable or hostless.
        assertTrue(rejected("http://").let { it.contains("host") || it.contains("Malformed") })
        assertTrue(rejected("://nope").let { it.contains("scheme") || it.contains("Malformed") })
    }

    @Test
    fun `an empty allowlist refuses all cleartext`() {
        val reason = LabEndpointPolicy.validate("http://10.0.2.2:5000/", emptySet())
        assertTrue(reason is LabEndpointPolicy.Decision.Rejected)
    }

    @Test
    fun `host matching is case insensitive`() {
        assertEquals("http://10.0.2.2:5000/", allowed("http://10.0.2.2:5000/".uppercase()))
    }
}
