package me.rerere.myagent.data.ai.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.URI

/**
 * Android 17 (API 37) 把「连私网地址」放进了运行时权限 `ACCESS_LOCAL_NETWORK`。
 *
 * 没有该权限时，socket 连接会静默超时 —— 表现为 MCP server 一直 Connecting 然后
 * 30 秒后 Error("timeout")，用户完全看不出是权限问题。这里在建立连接前把
 * 「需要局域网权限」翻译成一条可操作的中文错误。
 *
 * 公网地址与 localhost 不受影响：前者本来就不需要该权限，后者流量不出本机 UID。
 */
fun mcpLocalNetworkBlockReason(context: Context, url: String): String? {
    if (Build.VERSION.SDK_INT < 37) return null
    val host = runCatching { URI(url).host }.getOrNull()?.trim('[', ']') ?: return null
    if (!isPrivateNetworkHost(host)) return null
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_LOCAL_NETWORK,
    ) == PackageManager.PERMISSION_GRANTED
    return if (granted) null else LOCAL_NETWORK_HINT.format(host)
}

private const val LOCAL_NETWORK_HINT =
    "连接 %s 需要「局域网访问」权限，当前未授权。请到 设置 → 权限 授予后重试。"

/** 判定是否属于「局域网/本机链路」地址：回环、RFC1918、链路本地、Tailscale CGNAT、IPv6 ULA。 */
internal fun isPrivateNetworkHost(rawHost: String): Boolean {
    val host = rawHost.lowercase()
    if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan")) return true
    if (host.startsWith("127.")) return true
    if (host.startsWith("10.")) return true
    if (host.startsWith("192.168.")) return true
    if (host.startsWith("169.254.")) return true
    if (host.startsWith("172.")) {
        val second = host.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
        if (second in 16..31) return true
    }
    // 100.64.0.0/10 — CGNAT, used by Tailscale
    if (host.startsWith("100.")) {
        val second = host.removePrefix("100.").substringBefore('.').toIntOrNull() ?: return false
        if (second in 64..127) return true
    }
    if (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
    return false
}
