package com.sweetgirlfriend.pet.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Resolves addresses that a computer can actually use to reach the phone.
 *
 * Android devices commonly expose cellular, VPN, Wi-Fi and USB interfaces at
 * the same time. NetworkInterface enumeration order is undefined, so using its
 * first private address can accidentally publish a carrier/VPN address. Active
 * Wi-Fi and Ethernet link properties are therefore preferred and mobile/VPN
 * interfaces are never advertised as a LAN upload endpoint.
 */
internal object LanNetworkDiscovery {
    data class Candidate(
        val address: Inet4Address,
        val prefixLength: Int,
        val interfaceName: String,
        val priority: Int,
    )

    data class Result(
        val candidates: List<Candidate>,
        val diagnostics: List<String>,
    )

    fun inspect(context: Context): Result {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val preferred = mutableListOf<Candidate>()
        var activeIsCellular = false
        var activeIsVpn = false
        var connectivityInspectionFailed = false

        runCatching {
            if (connectivity != null) {
                val activeNetwork = connectivity.activeNetwork
                val networks = activeNetwork?.let(::listOf).orEmpty()
                networks.forEachIndexed { networkIndex, network ->
                    val capabilities = connectivity.getNetworkCapabilities(network) ?: return@forEachIndexed
                    if (network == activeNetwork) {
                        activeIsCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        activeIsVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                    val transportPriority = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 20
                        else -> return@forEachIndexed
                    }
                    val properties = connectivity.getLinkProperties(network) ?: return@forEachIndexed
                    val interfaceName = properties.interfaceName.orEmpty().ifBlank { "局域网" }
                    properties.linkAddresses.forEach { link ->
                        val address = link.address as? Inet4Address ?: return@forEach
                        if (LanAddressPolicy.isUsableAddress(address)) {
                            preferred += Candidate(
                                address = address,
                                prefixLength = link.prefixLength,
                                interfaceName = interfaceName,
                                priority = transportPriority + networkIndex,
                            )
                        }
                    }
                }
            }
        }.onFailure { connectivityInspectionFailed = true }

        // Hotspot and USB-tether interfaces are not always represented as an
        // active ConnectivityManager network, so include known physical LAN
        // interfaces as a lower-priority fallback.
        val fallback = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && LanAddressPolicy.isLanInterface(it.name) }
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                        val address = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
                        if (!LanAddressPolicy.isUsableAddress(address)) return@mapNotNull null
                        Candidate(
                            address = address,
                            prefixLength = interfaceAddress.networkPrefixLength.toInt(),
                            interfaceName = networkInterface.name,
                            priority = 100 + LanAddressPolicy.interfacePriority(networkInterface.name),
                        )
                    }
                }
        }.getOrDefault(emptyList())

        val candidates = (preferred + fallback)
            .distinctBy { it.address.hostAddress }
            .sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.address.hostAddress })

        val diagnostics = buildList {
            if (candidates.isEmpty()) {
                add("未检测到可供电脑访问的 WLAN、以太网、热点或 USB 网络地址。")
                if (activeIsCellular) add("当前主要使用移动数据；请让手机和电脑连接同一个 Wi-Fi。")
            } else {
                val primary = candidates.first()
                add("优先地址来自 ${primary.interfaceName}（${primary.address.hostAddress}）。")
                if (candidates.size > 1) add("检测到多个局域网地址；若首个地址不可达，可尝试下方其他地址。")
            }
            if (activeIsVpn) add("检测到 VPN；如无法访问，请暂时关闭手机或电脑的 VPN/代理后重试。")
            if (connectivityInspectionFailed) add("系统网络状态读取失败，当前地址来自网络接口回退检测。")
            if (isProbablyEmulator()) {
                add("当前可能是 Android 模拟器；电脑需执行 adb forward 后访问 localhost，模拟器地址通常不能直接打开。")
            }
            add("地址必须以 http:// 开头；访客 Wi-Fi/AP 隔离会阻止电脑访问手机。")
            add("更换 Wi-Fi、热点或 USB 网络后，地址会自动刷新；当前上传页面需要 IPv4 局域网。")
        }
        return Result(candidates, diagnostics)
    }

    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        return fingerprint.contains("generic") || fingerprint.contains("emulator") ||
            model.contains("sdk") || model.contains("emulator")
    }
}

internal object LanAddressPolicy {
    private val lanInterfacePrefixes = listOf(
        "wlan", "swlan", "ap", "eth", "en", "rndis", "usb", "p2p",
    )
    private val excludedInterfacePrefixes = listOf(
        "rmnet", "ccmni", "pdp", "wwan", "tun", "tap", "ppp", "dummy", "lo",
    )

    fun isLanInterface(name: String): Boolean {
        val normalized = name.lowercase()
        if (excludedInterfacePrefixes.any(normalized::startsWith)) return false
        return lanInterfacePrefixes.any(normalized::startsWith)
    }

    fun interfacePriority(name: String): Int {
        val normalized = name.lowercase()
        return when {
            normalized.startsWith("wlan") || normalized.startsWith("swlan") -> 0
            normalized.startsWith("eth") || normalized.startsWith("en") -> 10
            normalized.startsWith("ap") -> 20
            normalized.startsWith("rndis") || normalized.startsWith("usb") -> 30
            else -> 40
        }
    }

    fun isUsableAddress(address: Inet4Address): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isMulticastAddress && !address.isLinkLocalAddress

    fun isLanScoped(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        // RFC 6598 shared address space is used by several mesh/VPN and
        // tethering implementations and is still reachable with the token.
        return first == 100 && second in 64..127
    }

    fun sameSubnet(first: Inet4Address, second: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 1..32) return false
        val a = first.address
        val b = second.address
        var remaining = prefixLength
        for (index in a.indices) {
            if (remaining <= 0) return true
            val bits = minOf(remaining, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            if ((a[index].toInt() and mask) != (b[index].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }
}
