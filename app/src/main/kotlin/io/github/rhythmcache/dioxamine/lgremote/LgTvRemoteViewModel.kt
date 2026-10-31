package io.github.rhythmcache.dioxamine.lgremote

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

internal class LgTvRemoteViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val keyStore = LgTvSecureStore(appContext)
    private val pendingRequests = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val knownMacs = ConcurrentHashMap<String, String>()
    private val tlsManager = PinnedLgTvTrustManager()
    private val tlsClient: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tlsManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, tlsManager)
            // LG TV certificates do not identify the TV by its private IP. The peer is still
            // required to match the published LG TV/CA SHA-256 certificate pins above.
            .hostnameVerifier(HostnameVerifier { _, session ->
                runCatching {
                    LgTvRemoteProtocol.hasLgCertificatePin(
                        session.peerCertificates.filterIsInstance<X509Certificate>().toTypedArray(),
                    )
                }.getOrDefault(false)
            })
            .build()
    }
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    var devices by androidx.compose.runtime.mutableStateOf<List<LgTvDevice>>(emptyList())
        private set
    var selectedDevice by androidx.compose.runtime.mutableStateOf<LgTvDevice?>(null)
        private set
    var isScanning by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isConnecting by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isConnected by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isAwaitingApproval by androidx.compose.runtime.mutableStateOf(false)
        private set
    var status by androidx.compose.runtime.mutableStateOf("Ready")
        private set
    var errorMessage by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var selectedMac by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    @Volatile private var generation = 0
    @Volatile private var mainOpened = false
    @Volatile private var secureAttempt = true
    @Volatile private var registrationSent = false
    @Volatile private var currentHost: String? = null
    @Volatile private var currentClientKey: String? = null
    @Volatile private var inputSocketUrl: String? = null
    private var mainSocket: WebSocket? = null
    private var inputSocket: WebSocket? = null
    private var helloTimerStarted = false

    fun discover() {
        if (isScanning) return
        isScanning = true
        errorMessage = null
        status = "Searching for TVs on this Wi-Fi network…"
        viewModelScope.launch {
            try {
                val found = LgTvSsdpDiscovery.discover(appContext)
                val merged = (devices.filter { device -> found.none { it.host == device.host } } + found)
                    .distinctBy { it.host }
                    .sortedByDescending { it.likelyLg }
                devices = merged
                if (selectedDevice == null) selectedDevice = found.firstOrNull() ?: devices.firstOrNull()
                status = if (found.isEmpty()) {
                    "No TV found. Check the same Wi-Fi and enable LG Connect Apps on the TV, or enter its local IP."
                } else {
                    "Found ${found.size} network device(s). Select your LG TV to connect."
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "TV discovery failed."
                status = "Discovery failed."
            } finally {
                isScanning = false
            }
        }
    }

    fun addManualHost(hostInput: String): Boolean {
        val host = hostInput.trim().removePrefix("[").removeSuffix("]")
        if (!LgTvRemoteProtocol.isPrivateLanHost(host)) {
            errorMessage = "Enter a private local-network IP address, such as 192.168.1.25."
            return false
        }
        val device = devices.firstOrNull { it.host == host }
            ?: LgTvDevice(host, "LG webOS TV ($host)", likelyLg = true)
        if (devices.none { it.host == host }) devices = devices + device
        selectedDevice = device
        errorMessage = null
        status = "Selected ${device.label}."
        return true
    }

    fun selectDevice(device: LgTvDevice) {
        if (currentHost != device.host) disconnectSockets()
        selectedDevice = device
        selectedMac = knownMacs[device.host] ?: runCatching { keyStore.readMac(device.host) }.getOrNull()
        errorMessage = null
    }

    fun connect() {
        val device = selectedDevice
        if (device == null) {
            errorMessage = "Select or add a TV first."
            return
        }
        if (!LgTvRemoteProtocol.isPrivateLanHost(device.host)) {
            errorMessage = "For safety, Dioxamine only connects to private local-network IP addresses."
            return
        }
        disconnectSockets()
        val currentGeneration = ++generation
        currentHost = device.host
        isConnecting = true
        isConnected = false
        isAwaitingApproval = false
        errorMessage = null
        status = "Connecting securely to ${device.host}…"
        viewModelScope.launch {
            currentClientKey = withContext(Dispatchers.IO) { keyStore.readClientKey(device.host) }
            selectedMac = knownMacs[device.host] ?: withContext(Dispatchers.IO) { keyStore.readMac(device.host) }
            openMainSocket(currentGeneration, device.host, secure = true)
        }
    }

    fun forgetPairing() {
        val host = selectedDevice?.host ?: return
        disconnectSockets()
        viewModelScope.launch(Dispatchers.IO) { keyStore.forget(host) }
        currentClientKey = null
        selectedMac = null
        status = "Saved pairing for this TV was removed. Connect again to pair."
        errorMessage = null
    }

    fun pressButton(name: String) {
        if (!isConnected) return
        val socket = inputSocket ?: return
        if (!socket.send(LgTvRemoteProtocol.inputFrame("button", mapOf("name" to name)))) {
            errorMessage = "The remote-control socket is not available. Reconnect to the TV."
        }
    }

    fun powerAction() {
        if (isConnected) {
            sendRequest(LgTvRemoteProtocol.TURN_OFF_URI, "dioxamine_turn_off") { result ->
                if (result.optBoolean("returnValue", false)) status = "Power-off command sent."
                else status = "Power-off command sent; the TV may disconnect while shutting down."
            }
            return
        }
        val device = selectedDevice ?: return
        val mac = selectedMac ?: knownMacs[device.host] ?: runCatching { keyStore.readMac(device.host) }.getOrNull()
        if (mac.isNullOrBlank()) {
            errorMessage = "To turn the TV on, pair while it is on first so Dioxamine can learn its MAC address. Enable Wake-on-LAN / Turn on via Wi-Fi in TV settings."
            return
        }
        viewModelScope.launch {
            try {
                sendWakeOnLan(mac)
                status = "Wake-on-LAN packet sent. If enabled on the TV, it should wake shortly."
                delay(2_500)
                if (!isConnected && selectedDevice?.host == device.host) connect()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Wake-on-LAN failed."
            }
        }
    }

    private suspend fun sendWakeOnLan(mac: String) = withContext(Dispatchers.IO) {
        val bytes = mac.split(Regex("[:-]" )).map { it.toInt(16).toByte() }.toByteArray()
        require(bytes.size == 6) { "Invalid TV MAC address." }
        val packetData = ByteArray(6 + 16 * bytes.size)
        java.util.Arrays.fill(packetData, 0, 6, 0xff.toByte())
        repeat(16) { index -> System.arraycopy(bytes, 0, packetData, 6 + index * bytes.size, bytes.size) }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val target = InetSocketAddress(InetAddress.getByName("255.255.255.255"), 9)
            repeat(3) {
                socket.send(DatagramPacket(packetData, packetData.size, target))
                delay(100)
            }
        }
    }

    private fun openMainSocket(generationId: Int, host: String, secure: Boolean) {
        if (generationId != generation) return
        secureAttempt = secure
        mainOpened = false
        registrationSent = false
        helloTimerStarted = false
        val scheme = if (secure) "wss" else "ws"
        val port = if (secure) 3001 else 3000
        val urlHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val url = "$scheme://$urlHost:$port/"
        val request = Request.Builder().url(url).build()
        val client = if (secure) tlsClient else plainClient
        val attemptStartedSecure = secure
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (generationId != generation) {
                        webSocket.close(1000, "stale connection")
                        return@launch
                    }
                    mainSocket = webSocket
                    mainOpened = true
                    status = if (attemptStartedSecure) "Secure link established; starting webOS handshake…" else "Connected to legacy webOS transport; pairing…"
                    val hello = LgTvRemoteProtocol.message("hello", "dioxamine_hello")
                    webSocket.send(hello.toString())
                    if (!helloTimerStarted) {
                        helloTimerStarted = true
                        viewModelScope.launch {
                            delay(1_500)
                            if (generationId == generation && mainOpened && !registrationSent) requestSystemInfoBeforeRegistration(generationId)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generationId == generation) {
                    viewModelScope.launch(Dispatchers.Main.immediate) { handleMainMessage(generationId, text) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generationId != generation) return
                mainOpened = false
                if (attemptStartedSecure && !isTlsTrustFailure(t)) {
                    webSocket.cancel()
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        if (generationId != generation) return@launch
                        status = "Secure port unavailable; trying the older local transport…"
                        openMainSocket(generationId, host, secure = false)
                    }
                } else {
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        if (generationId != generation) return@launch
                        isConnecting = false
                        isConnected = false
                        isAwaitingApproval = false
                        val reason = t.message ?: "Connection failed."
                        errorMessage = if (isTlsTrustFailure(t)) {
                            "The TV's TLS certificate did not match the known LG webOS certificate pins. Connection stopped for safety."
                        } else reason
                        status = "Could not connect to ${host}. Check that it is on and LG Connect Apps is enabled."
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (generationId == generation && (isConnected || isConnecting)) {
                        isConnected = false
                        isConnecting = false
                        isAwaitingApproval = false
                        status = "TV connection closed. Reconnect to use the remote."
                    }
                }
            }
        })
    }

    private fun requestSystemInfoBeforeRegistration(generationId: Int) {
        if (generationId != generation || registrationSent) return
        val socket = mainSocket ?: return
        val message = LgTvRemoteProtocol.message(
            "request",
            LgTvRemoteProtocol.SYSTEM_INFO_ID,
            LgTvRemoteProtocol.SYSTEM_INFO_URI,
        )
        socket.send(message.toString())
        viewModelScope.launch {
            delay(2_000)
            sendRegistrationOnce(generationId)
        }
    }

    private fun sendRegistrationOnce(generationId: Int) {
        if (generationId != generation || registrationSent) return
        registrationSent = true
        isAwaitingApproval = currentClientKey.isNullOrBlank()
        status = if (isAwaitingApproval) {
            "A connection request will appear on the TV. Approve it with the TV remote."
        } else {
            "Using the saved TV pairing…"
        }
        mainSocket?.send(LgTvRemoteProtocol.registrationMessage(currentClientKey).toString())
    }

    private fun handleMainMessage(generationId: Int, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = message.optString("type")
        val id = message.optString("id")
        val payload = message.optJSONObject("payload") ?: JSONObject()

        if (id == "dioxamine_hello" || type == "hello") {
            requestSystemInfoBeforeRegistration(generationId)
            return
        }
        if (id == LgTvRemoteProtocol.SYSTEM_INFO_ID) {
            sendRegistrationOnce(generationId)
            return
        }
        if (id == LgTvRemoteProtocol.REGISTER_ID || type == "registered" || type == "response") {
            if (payload.optString("pairingType") == "PROMPT") {
                isAwaitingApproval = true
                status = "Approve the connection request on your LG TV. A phone PIN is normally not used."
                return
            }
            if (type == "error") {
                val error = message.optString("error", "TV declined or rejected pairing.")
                if (id == LgTvRemoteProtocol.REGISTER_ID || isAwaitingApproval) {
                    isAwaitingApproval = false
                    isConnecting = false
                    errorMessage = error
                    status = "Pairing was not completed."
                }
                return
            }
            val receivedKey = payload.optString("client-key").takeIf { it.isNotBlank() }
            if (type == "registered" || receivedKey != null) {
                currentClientKey = receivedKey ?: currentClientKey
                val host = currentHost
                val key = currentClientKey
                if (host != null && key != null) viewModelScope.launch(Dispatchers.IO) { keyStore.writeClientKey(host, key) }
                isAwaitingApproval = false
                status = "Paired with the TV; preparing remote controls…"
                requestInputSocket(generationId)
                return
            }
        }

        pendingRequests.remove(id)?.invoke(message)
    }

    private fun requestInputSocket(generationId: Int) {
        val socket = mainSocket ?: return
        val id = LgTvRemoteProtocol.INPUT_SOCKET_ID
        pendingRequests[id] = callback@{ response ->
            if (generationId != generation) return@callback
            val payload = response.optJSONObject("payload") ?: JSONObject()
            val rawUrl = payload.optString("socketPath").takeIf { it.isNotBlank() }
            if (response.optString("type") == "error" || rawUrl == null) {
                isConnecting = false
                errorMessage = "The TV paired, but did not provide its remote-control input socket."
                status = "Remote input is unavailable on this TV."
                return@callback
            }
            val selectedHost = currentHost ?: return@callback
            inputSocketUrl = runCatching { normalizeInputSocketUrl(rawUrl, selectedHost, secureAttempt) }.getOrElse {
                isConnecting = false
                errorMessage = "The TV returned a remote socket outside the private LAN or on an unsupported port."
                status = "Remote input was blocked for safety."
                return@callback
            }
            openInputSocket(generationId, inputSocketUrl!!)
        }
        socket.send(LgTvRemoteProtocol.message("request", id, LgTvRemoteProtocol.INPUT_SOCKET_URI).toString())
    }

    private fun normalizeInputSocketUrl(raw: String, host: String, secure: Boolean): String {
        val urlHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        if (raw.startsWith("ws://") || raw.startsWith("wss://")) {
            val uri = URI(raw)
            val remoteHost = uri.host ?: throw IllegalArgumentException("Missing socket host")
            require(LgTvRemoteProtocol.isPrivateLanHost(remoteHost)) { "Socket host is not private" }
            require(uri.port == 3000 || uri.port == 3001) { "Unsupported socket port" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Unexpected socket URL fields" }
            return raw
        }
        val scheme = if (secure) "wss" else "ws"
        val port = if (secure) 3001 else 3000
        return "$scheme://$urlHost:$port/${raw.trimStart('/')}"
    }

    private fun openInputSocket(generationId: Int, url: String) {
        val secure = url.startsWith("wss://")
        val client = if (secure) tlsClient else plainClient
        val request = runCatching { Request.Builder().url(url).build() }.getOrElse {
            isConnecting = false
            errorMessage = "The TV returned an invalid remote socket address."
            return
        }
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (generationId != generation) {
                        webSocket.close(1000, "stale connection")
                        return@launch
                    }
                    inputSocket = webSocket
                    isConnecting = false
                    isConnected = true
                    isAwaitingApproval = false
                    status = "Connected to ${currentHost}. The remote is ready."
                    requestConnectionInfo(generationId)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generationId != generation) return
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (generationId != generation) return@launch
                    inputSocket = null
                    isConnecting = false
                    isConnected = false
                    errorMessage = if (isTlsTrustFailure(t)) {
                        "The remote socket certificate did not match the known LG webOS certificate pins."
                    } else {
                        t.message ?: "Could not open the TV's remote-control input socket."
                    }
                    status = "Paired, but remote input could not be opened."
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (generationId == generation) {
                        inputSocket = null
                        isConnected = false
                        status = "Remote input connection closed. Reconnect to continue."
                    }
                }
            }
        })
    }

    private fun requestConnectionInfo(generationId: Int) {
        val socket = mainSocket ?: return
        val id = LgTvRemoteProtocol.CONNECTION_INFO_ID
        pendingRequests[id] = callback@{ response ->
            if (generationId != generation || response.optString("type") == "error") return@callback
            val payload = response.optJSONObject("payload") ?: return@callback
            val mac = payload.optJSONObject("wifiInfo")?.optString("macAddress")
                ?.takeIf { isValidMac(it) }
                ?: payload.optJSONObject("wiredInfo")?.optString("macAddress")?.takeIf { isValidMac(it) }
                ?: return@callback
            currentHost?.let { host ->
                selectedMac = mac.uppercase()
                knownMacs[host] = mac.uppercase()
                viewModelScope.launch(Dispatchers.IO) { keyStore.writeMac(host, mac) }
            }
        }
        socket.send(LgTvRemoteProtocol.message("request", id, LgTvRemoteProtocol.CONNECTION_INFO_URI).toString())
    }

    private fun sendRequest(uri: String, id: String, callback: (JSONObject) -> Unit) {
        val socket = mainSocket
        if (socket == null || !isConnected) return
        pendingRequests[id] = callback
        socket.send(LgTvRemoteProtocol.message("request", id, uri).toString())
    }

    private fun isValidMac(mac: String): Boolean = mac.matches(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"))

    private fun isTlsTrustFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is CertificateException || current is SSLPeerUnverifiedException || current is SSLException) return true
            current = current.cause
        }
        return false
    }

    private fun disconnectSockets() {
        generation++
        mainOpened = false
        registrationSent = false
        isConnected = false
        isConnecting = false
        isAwaitingApproval = false
        pendingRequests.clear()
        runCatching { inputSocket?.close(1000, "disconnect") }
        runCatching { mainSocket?.close(1000, "disconnect") }
        inputSocket = null
        mainSocket = null
        inputSocketUrl = null
    }

    override fun onCleared() {
        disconnectSockets()
        super.onCleared()
    }
}

private class PinnedLgTvTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not used by the LG TV remote client.")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificates = chain ?: throw CertificateException("The TV sent no TLS certificate.")
        certificates.forEach { it.checkValidity() }
        if (!LgTvRemoteProtocol.hasLgCertificatePin(certificates)) {
            throw CertificateException("TLS certificate did not match known LG webOS certificate pins.")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
