package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which durable address one session's journal is read through.
 *
 * Opening a child as an ordinary session is not a cosmetic mistake: the host refuses it outright,
 * because the parent is the authority a cold read verifies against.
 */
class SessionAddressTest {

    @Test
    fun `a subagent child is addressed through its parent`() {
        val address = sessionAddressFor(
            sessionId = "kid",
            origin = "subagent",
            parentSessionId = "root",
            subagentMode = "continuable",
        )

        assertEquals(SessionAddress.Subagent(parentSessionId = "root", childSessionId = "kid", mode = "continuable"), address)
    }

    @Test
    fun `a child of unknown mode asks the host to skip its cross-check`() {
        val address = sessionAddressFor(
            sessionId = "kid",
            origin = "subagent",
            parentSessionId = "root",
            subagentMode = null,
        )

        assertEquals(SUBAGENT_MODE_UNKNOWN, (address as SessionAddress.Subagent).mode)
        val encoded = encodeToJsonElement(SessionAddress.serializer(), address)
        assertEquals("unknown", encoded.jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `a fork is an ordinary session even though it records a parent`() {
        val address = sessionAddressFor(
            sessionId = "forked",
            origin = null,
            parentSessionId = "root",
            subagentMode = null,
        )

        assertEquals(SessionAddress.Session(sessionId = "forked"), address)
    }

    @Test
    fun `a root session is an ordinary session`() {
        assertEquals(
            SessionAddress.Session(sessionId = "root"),
            sessionAddressFor(sessionId = "root", origin = null, parentSessionId = null, subagentMode = null),
        )
    }

    @Test
    fun `a subagent address round-trips through JSON`() {
        val address = SessionAddress.Subagent(parentSessionId = "root", childSessionId = "kid", mode = "one-shot")
        val encoded = encodeToJsonElement(SessionAddress.serializer(), address)

        assertEquals(address, decodeFromJsonElement(SessionAddress.serializer(), encoded))
    }

    @Test
    fun `the descriptor mode reads from the child's own subagent projection`() {
        assertEquals("one-shot", subagentModeOf(buildJsonObject { put("mode", "one-shot"); put("seq", 0) }))
        assertEquals("continuable", subagentModeOf(buildJsonObject { put("mode", "continuable") }))
    }

    @Test
    fun `an absent or unfamiliar descriptor mode reads as unknown`() {
        assertNull(subagentModeOf(null))
        assertNull(subagentModeOf(JsonNull))
        assertNull(subagentModeOf(JsonObject(emptyMap())))
        assertNull(subagentModeOf(buildJsonObject { put("seq", 3) }))
        assertNull(subagentModeOf(buildJsonObject { put("mode", "") }))
    }
}
