package com.sweetgirlfriend.pet.app

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressPolicyTest {
    @Test
    fun `physical LAN interfaces are selected but cellular and VPN are excluded`() {
        listOf("wlan0", "swlan0", "eth0", "ap0", "rndis0", "p2p0").forEach {
            assertTrue(it, LanAddressPolicy.isLanInterface(it))
        }
        listOf("rmnet_data0", "ccmni0", "tun0", "ppp0", "lo").forEach {
            assertFalse(it, LanAddressPolicy.isLanInterface(it))
        }
    }

    @Test
    fun `private shared and link local peers are accepted`() {
        listOf("127.0.0.1", "10.23.4.5", "172.16.2.3", "192.168.1.20", "100.64.9.8", "169.254.8.7")
            .forEach { assertTrue(it, LanAddressPolicy.isLanScoped(InetAddress.getByName(it))) }
        assertFalse(LanAddressPolicy.isLanScoped(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `public enterprise WiFi peer can be accepted only on advertised subnet`() {
        val phone = ipv4("203.0.113.20")
        assertTrue(LanAddressPolicy.sameSubnet(phone, ipv4("203.0.113.88"), 24))
        assertFalse(LanAddressPolicy.sameSubnet(phone, ipv4("203.0.114.1"), 24))
        assertTrue(LanAddressPolicy.sameSubnet(phone, ipv4("203.0.113.20"), 32))
        assertFalse(LanAddressPolicy.sameSubnet(phone, ipv4("203.0.113.21"), 32))
    }

    @Test
    fun `loopback and link local addresses are not advertised`() {
        assertFalse(LanAddressPolicy.isUsableAddress(ipv4("127.0.0.1")))
        assertFalse(LanAddressPolicy.isUsableAddress(ipv4("169.254.3.4")))
        assertTrue(LanAddressPolicy.isUsableAddress(ipv4("192.168.50.9")))
    }

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
