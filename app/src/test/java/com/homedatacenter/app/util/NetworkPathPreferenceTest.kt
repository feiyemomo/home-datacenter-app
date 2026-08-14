package com.homedatacenter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NetworkPathPreference.fromName resolves the persisted preference string
 * back to an enum. Unknown / null values must fall back to AUTO (the
 * default) so a corrupted or legacy prefs value never wedges the URL
 * resolver into a non-existent path.
 */
class NetworkPathPreferenceTest {

    @Test
    fun knownNames_resolveToTheirEnum() {
        assertEquals(NetworkPathPreference.AUTO, NetworkPathPreference.fromName("AUTO"))
        assertEquals(NetworkPathPreference.LAN, NetworkPathPreference.fromName("LAN"))
        assertEquals(NetworkPathPreference.IPV6_DIRECT, NetworkPathPreference.fromName("IPV6_DIRECT"))
        assertEquals(NetworkPathPreference.RELAY, NetworkPathPreference.fromName("RELAY"))
    }

    @Test
    fun unknownOrNull_fallsBackToAuto() {
        assertEquals(NetworkPathPreference.AUTO, NetworkPathPreference.fromName(null))
        assertEquals(NetworkPathPreference.AUTO, NetworkPathPreference.fromName(""))
        assertEquals(NetworkPathPreference.AUTO, NetworkPathPreference.fromName("  "))
        assertEquals(NetworkPathPreference.AUTO, NetworkPathPreference.fromName("TUNNEL"))
        assertEquals(NetworkPathPreference.AUTO, NetworkPathPreference.fromName("auto"))
        assertEquals(NetworkPathPreference.AUTO, NetworkPathPreference.fromName("LAN "))
    }

    @Test
    fun labels_areHumanReadable() {
        assertEquals("自动", NetworkPathPreference.AUTO.label)
        assertEquals("局域网", NetworkPathPreference.LAN.label)
        assertEquals("IPv6 直连", NetworkPathPreference.IPV6_DIRECT.label)
        assertEquals("远程 (Tunnel)", NetworkPathPreference.RELAY.label)
    }
}