package com.boneink.picapica.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class ConnectionRoute(val title: String, val subtitle: String) {
    AUTO("自动", "自动尝试可用线路"),
    SYSTEM("系统网络", "使用当前系统网络设置"),
    EDGE_A("线路一", "手动选择第一条线路"),
    EDGE_B("线路二", "手动选择第二条线路"),
}

/** Only known PicACG hosts are routed. TLS still validates the original hostname. */
class PicaDns(private val route: () -> ConnectionRoute, private val system: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val mode = route()
        if (mode == ConnectionRoute.SYSTEM || hostname.lowercase() !in HOSTS) return system.lookup(hostname)
        val values = when (mode) {
            ConnectionRoute.EDGE_A -> listOf("104.19.53.76")
            ConnectionRoute.EDGE_B -> listOf("104.21.235.3")
            else -> listOf("104.19.53.76", "172.67.80.1", "172.67.7.24", "104.21.235.3")
        }
        val edges = values.map { ip -> InetAddress.getByAddress(hostname, ip.split('.').map { it.toInt().toByte() }.toByteArray()) }
        val fallback = if (mode == ConnectionRoute.AUTO) runCatching { system.lookup(hostname) }.getOrDefault(emptyList()) else emptyList()
        return (edges + fallback).distinctBy { it.hostAddress }
    }
    companion object {
        // Primary API address from the original client's public /init response, checked 2026-10-27.
        // Historical fallbacks from niuhuan/pica-rust (MIT). TLS still verifies the original hostname.
        private val HOSTS = setOf("picaapi.picacomic.com", "img.picacomic.com", "storage1.picacomic.com", "s2.picacomic.com", "s3.picacomic.com",
            "storage-b.picacomic.com", "storage.tipatipa.xyz", "storage.wikawika.xyz")
    }
}

class NetworkStack(context: Context, storageName: String, val imageQuality: () -> String = { "original" }) {
    private val prefs = context.getSharedPreferences("$storageName.network", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(runCatching {
        ConnectionRoute.valueOf(prefs.getString("route", ConnectionRoute.AUTO.name)!!)
    }.getOrDefault(ConnectionRoute.AUTO))
    val route = selected.asStateFlow()
    val apiBackoff = ApiBackoff(load = { prefs.getLong("apiNotBefore", 0L) }, save = { prefs.edit().putLong("apiNotBefore", it).apply() })
    val imageHttp: OkHttpClient = OkHttpClient.Builder().dns(PicaDns({ selected.value }))
        .connectTimeout(7, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    val apiHttp: OkHttpClient = imageHttp.newBuilder().callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build()
    fun select(value: ConnectionRoute) {
        selected.value = value
        prefs.edit().putString("route", value.name).apply()
        apiHttp.connectionPool.evictAll()
    }
}
