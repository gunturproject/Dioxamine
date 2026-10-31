package io.github.rhythmcache.dioxamine.lgremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LgTvRemoteProtocolTest {
    @Test
    fun parsesSsdpHeadersCaseInsensitively() {
        val headers = LgTvRemoteProtocol.parseSsdpHeaders(
            "HTTP/1.1 200 OK\r\nSERVER: webOS/9.0 UPnP/1.0\r\nUSN: uuid:LG-TV::renderer\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )

        assertEquals("webOS/9.0 UPnP/1.0", headers["server"])
        assertEquals("uuid:LG-TV::renderer", headers["usn"])
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", headers["st"])
    }

    @Test
    fun onlyAcceptsPrivateOrLinkLocalAddresses() {
        assertTrue(LgTvRemoteProtocol.isPrivateLanHost("192.168.1.25"))
        assertTrue(LgTvRemoteProtocol.isPrivateLanHost("10.0.0.12"))
        assertTrue(LgTvRemoteProtocol.isPrivateLanHost("172.20.0.8"))
        assertTrue(LgTvRemoteProtocol.isPrivateLanHost("169.254.1.2"))
        assertTrue(LgTvRemoteProtocol.isPrivateLanHost("fc00::1"))
        assertFalse(LgTvRemoteProtocol.isPrivateLanHost("8.8.8.8"))
        assertFalse(LgTvRemoteProtocol.isPrivateLanHost("tv.example.com"))
    }

    @Test
    fun createsTheWebOsLineDelimitedButtonFrame() {
        assertEquals(
            "type:button\nname:HOME\n\n",
            LgTvRemoteProtocol.inputFrame("button", mapOf("name" to "HOME")),
        )
    }

    @Test
    fun inputFrameIncludesMultipleFieldsAndFinalBlankLine() {
        assertEquals(
            "type:move\ndx:5\ndy:-2\n\n",
            LgTvRemoteProtocol.inputFrame("move", linkedMapOf("dx" to "5", "dy" to "-2")),
        )
    }
}
