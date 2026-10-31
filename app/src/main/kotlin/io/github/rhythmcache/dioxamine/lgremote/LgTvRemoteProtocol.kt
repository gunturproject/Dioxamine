package io.github.rhythmcache.dioxamine.lgremote

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest

internal data class LgTvDevice(
    val host: String,
    val label: String,
    val server: String = "",
    val likelyLg: Boolean = true,
)

internal object LgTvRemoteProtocol {
    const val INPUT_SOCKET_URI = "ssap://com.webos.service.networkinput/getPointerInputSocket"
    const val CONNECTION_INFO_URI = "ssap://com.webos.service.connectionmanager/getinfo"
    const val TURN_OFF_URI = "ssap://system/turnOff"
    const val SYSTEM_INFO_URI = "ssap://system/getSystemInfo"
    const val REGISTER_ID = "dioxamine_register"
    const val SYSTEM_INFO_ID = "dioxamine_sysinfo"
    const val INPUT_SOCKET_ID = "dioxamine_input_socket"
    const val CONNECTION_INFO_ID = "dioxamine_connection_info"

    // LG's webOS TV certificate fingerprints, published by the lgtv2 project.
    // The leaf and intermediate are checked; TLS trust-all is never used.
    private val lgCertificatePins = setOf(
        "E2BD6464D3F51C1B95B7697D9D6773C33D9412EBA0299C568C34937D3FE68AA0",
        "11C5B1C5907750ABB9DA2A6665CCCE2BB288A583F45A3339E71F87BF2F808552",
    )

    fun registrationMessage(clientKey: String?): JSONObject {
        val permissions = JSONArray().apply {
            put("CONTROL_AUDIO")
            put("CONTROL_INPUT_JOYSTICK")
            put("CONTROL_INPUT_MEDIA_PLAYBACK")
            put("CONTROL_INPUT_TV")
            put("CONTROL_MOUSE_AND_KEYBOARD")
            put("CONTROL_POWER")
            put("READ_CURRENT_CHANNEL")
            put("READ_NETWORK_STATE")
            put("READ_POWER_STATE")
        }
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", JSONObject()
                .put("manifestVersion", 1)
                .put("appVersion", "1.0")
                .put("permissions", permissions))
        if (!clientKey.isNullOrBlank()) payload.put("client-key", clientKey)
        return JSONObject()
            .put("type", "register")
            .put("id", REGISTER_ID)
            .put("payload", payload)
    }

    fun message(type: String, id: String, uri: String? = null, payload: JSONObject = JSONObject()): JSONObject {
        val message = JSONObject().put("type", type).put("id", id).put("payload", payload)
        if (uri != null) message.put("uri", uri)
        return message
    }

    fun inputFrame(type: String, payload: Map<String, String> = emptyMap()): String = buildString {
        append("type:").append(type).append('\n')
        payload.forEach { (key, value) -> append(key).append(':').append(value).append('\n') }
        append('\n')
    }

    fun parseSsdpHeaders(response: String): Map<String, String> = buildMap {
        response.lineSequence().drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) put(line.substring(0, separator).trim().lowercase(), line.substring(separator + 1).trim())
        }
    }

    fun hasLgCertificatePin(certificates: Array<out java.security.cert.X509Certificate>): Boolean = certificates.any { cert ->
        runCatching {
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02X".format(it) }
            fingerprint in lgCertificatePins
        }.getOrDefault(false)
    }

    fun isPrivateLanHost(raw: String): Boolean {
        val host = raw.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty() || host.any { !(it.isDigit() || it in "abcdefABCDEF:.") }) return false
        return runCatching {
            val address = InetAddress.getByName(host)
            when (address) {
                is Inet4Address -> {
                    val b = address.address.map { it.toInt() and 0xff }
                    (b[0] == 10) ||
                        (b[0] == 172 && b[1] in 16..31) ||
                        (b[0] == 192 && b[1] == 168) ||
                        (b[0] == 169 && b[1] == 254) ||
                        address.isLoopbackAddress
                }
                is Inet6Address -> {
                    val first = address.address[0].toInt() and 0xff
                    ((first and 0xfe) == 0xfc) || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isLoopbackAddress
                }
                else -> false
            }
        }.getOrDefault(false)
    }
}

internal object LgTvSsdpDiscovery {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

    suspend fun discover(context: Context, timeoutMs: Long = 5_000L): List<LgTvDevice> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("dioxamine-lg-tv-discovery")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            val group = InetAddress.getByName(SSDP_ADDRESS)
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 350
                socket.bind(InetSocketAddress(0))
                val query = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $SEARCH_TARGET\r\n" +
                        "USER-AGENT: Android/1.0 UPnP/1.1 Dioxamine/1.0\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                repeat(2) {
                    socket.send(DatagramPacket(query, query.size, group, SSDP_PORT))
                    Thread.sleep(180)
                }
                val results = linkedMapOf<String, LgTvDevice>()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val bytes = ByteArray(8192)
                    val packet = DatagramPacket(bytes, bytes.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val host = packet.address.hostAddress?.substringBefore('%') ?: continue
                    if (!LgTvRemoteProtocol.isPrivateLanHost(host)) continue
                    val response = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val headers = LgTvRemoteProtocol.parseSsdpHeaders(response)
                    val server = headers["server"].orEmpty()
                    val usn = headers["usn"].orEmpty()
                    val isLg = server.contains("lg", true) || server.contains("webos", true) || usn.contains("lge", true)
                    val existing = results[host]
                    if (existing == null || (!existing.likelyLg && isLg)) {
                        val name = if (isLg) "LG webOS TV ($host)" else "MediaRenderer ($host)"
                        results[host] = LgTvDevice(host = host, label = name, server = server, likelyLg = isLg)
                    }
                }
                results.values.sortedByDescending { it.likelyLg }
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }
}
