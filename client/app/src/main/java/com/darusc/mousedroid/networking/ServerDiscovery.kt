package com.darusc.mousedroid.networking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class DiscoveredServer(
    val name: String,
    val address: String,
    val port: Int = ServerDiscovery.DEFAULT_PORT
)

object ServerDiscovery {
    const val DEFAULT_PORT = 6969
    const val DISCOVERY_REQUEST = "MOUSEDROID_DISCOVER"
    private const val RESPONSE_PREFIX = "MOUSEDROID_SERVER"

    suspend fun discover(timeoutMs: Int = 1200): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val servers = linkedMapOf<String, DiscoveredServer>()
        val payload = DISCOVERY_REQUEST.toByteArray(StandardCharsets.UTF_8)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            socket.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName("255.255.255.255"),
                    DEFAULT_PORT
                )
            )

            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < timeoutMs) {
                try {
                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseResponse(
                        String(packet.data, 0, packet.length, StandardCharsets.UTF_8),
                        packet.address.hostAddress
                    )?.let { servers["${it.address}:${it.port}"] = it }
                } catch (_: Exception) {
                    break
                }
            }
        }

        servers.values.toList()
    }

    fun pairingUri(server: DiscoveredServer): String {
        return "mousedroid://pair?name=${encode(server.name)}&host=${server.address}&port=${server.port}"
    }

    fun parsePairingPayload(payload: String): DiscoveredServer? {
        return try {
            val uri = URI(payload.trim())
            if (uri.scheme != "mousedroid" || uri.host != "pair") return null

            val params = uri.rawQuery
                ?.split("&")
                ?.mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) decode(parts[0]) to decode(parts[1]) else null
                }
                ?.toMap()
                ?: return null

            val host = params["host"] ?: return null
            val name = params["name"] ?: host
            val port = params["port"]?.toIntOrNull() ?: DEFAULT_PORT
            DiscoveredServer(name, host, port)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseResponse(response: String, fallbackAddress: String?): DiscoveredServer? {
        val parts = response.split("|")
        if (parts.firstOrNull() != RESPONSE_PREFIX) return null

        val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Mousedroid Server"
        val address = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: fallbackAddress ?: return null
        val port = parts.getOrNull(3)?.toIntOrNull() ?: DEFAULT_PORT
        return DiscoveredServer(name, address, port)
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
