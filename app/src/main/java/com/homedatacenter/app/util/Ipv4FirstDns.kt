package com.homedatacenter.app.util

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Resolves A records before AAAA records.
 *
 * Many Chinese carriers give the phone a global IPv6 address (2409:…)
 * while the route to Cloudflare's anycast prefix is silently
 * blackholed. OkHttp tries the AAAA answer first, spends the whole
 * connect timeout on it, and the call fails with
 *
 *   failed to connect to api.feiyemomo.top/2606:4700:3033::6815:a3d
 *   (port 443) after 5000ms
 *
 * even though the A record answers in ~230 ms. That made the backend
 * look unreachable: /fast-status discovery timed out, the reachability
 * probe flipped to offline, the red "无法连接到家庭服务器" banner
 * appeared, and every request paid a multi-second stall before retrying.
 *
 * Hosts that only have AAAA answers are returned unchanged, and the
 * IPv6-direct NAS path uses a literal address so it never resolves here.
 */
object Ipv4FirstDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        if (resolved.size < 2) return resolved

        val v4 = resolved.filterIsInstance<Inet4Address>()
        if (v4.isEmpty()) return resolved

        val v6 = resolved.filterIsInstance<Inet6Address>()
        return v4 + v6
    }
}
