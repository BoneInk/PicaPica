package com.boneink.picapica

import com.boneink.picapica.network.*
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class NetworkRoutingTest {
    private val system = object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
    }
    @Test fun routesOnlyKnownHostsAndRetainsOriginalTlsHostname() {
        val dns = PicaDns({ ConnectionRoute.AUTO }, system)
        assertEquals(listOf("104.19.53.76", "172.67.80.1", "172.67.7.24", "104.21.235.3", "127.0.0.1"), dns.lookup("picaapi.picacomic.com").map { it.hostAddress })
        assertEquals("picaapi.picacomic.com", dns.lookup("picaapi.picacomic.com").first().hostName)
        assertEquals(listOf("127.0.0.1"), dns.lookup("example.com").map { it.hostAddress })
        assertEquals(listOf("127.0.0.1"), dns.lookup("127.0.0.1").map { it.hostAddress })
    }
    @Test fun canSelectSystemOrSingleRouteWithoutChangingEndpoint() {
        var route = ConnectionRoute.SYSTEM
        val dns = PicaDns({ route }, system)
        assertEquals(listOf("127.0.0.1"), dns.lookup("picaapi.picacomic.com").map { it.hostAddress })
        route = ConnectionRoute.EDGE_A
        assertEquals(listOf("104.19.53.76"), dns.lookup("picaapi.picacomic.com").map { it.hostAddress })
        route = ConnectionRoute.EDGE_B
        assertEquals(listOf("104.21.235.3"), dns.lookup("picaapi.picacomic.com").map { it.hostAddress })
    }
    @org.junit.Test fun redirectedCoverHostUsesVerifiedHostnameWhenSystemDnsIsUnavailable() {
        val dns = com.boneink.picapica.network.PicaDns({ com.boneink.picapica.network.ConnectionRoute.AUTO }, object : okhttp3.Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException("fixture DNS unavailable") })
        val addresses = dns.lookup("img.picacomic.com")
        org.junit.Assert.assertEquals("104.19.53.76", addresses.first().hostAddress)
        org.junit.Assert.assertTrue(addresses.all { it.hostName == "img.picacomic.com" })
        try { dns.lookup("unrelated.example.invalid"); org.junit.Assert.fail() } catch (_: java.net.UnknownHostException) { }
    }
}
