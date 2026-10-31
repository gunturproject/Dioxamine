package io.github.rhythmcache.dioxamine.adb

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume

private const val AUTOROOT_URL = "https://github.com/throwaway96/faultmanager-autoroot"
private const val AUTOROOT_FILE_URL = "https://raw.githubusercontent.com/throwaway96/faultmanager-autoroot/a972c95f4f4dbacdb72215409ea0c9eff39dabc1/autoroot.sh"
private const val AUTOROOT_SHA256 = "66a8a2816567a3c3d29768095873d347ec6bb2cbf60208979d15d7a42525d4d0"
private const val REMOTE_SCRIPT = "/tmp/autoroot.sh"

@Composable
fun WebOsRootScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultScript = remember(context) {
        context.assets.open("webos/autoroot.sh").use { it.readBytes() }
    }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9922") }
    var username by remember { mutableStateOf("prisoner") }
    var keyPassphrase by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf<ByteArray?>(null) }
    var privateKeyName by remember { mutableStateOf<String?>(null) }
    var scriptBytes by remember { mutableStateOf<ByteArray?>(defaultScript) }
    var scriptHash by remember { mutableStateOf<String?>(sha256(defaultScript)) }
    var scriptName by remember { mutableStateOf("autoroot.sh (bawaan)") }
    var debug by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingHostKey by remember { mutableStateOf<String?>(null) }
    var hostKeyDecision by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var showRunConfirmation by remember { mutableStateOf(false) }
    var showKeyFetchConfirmation by remember { mutableStateOf(false) }

    val scriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: kotlin.error("Tidak dapat membaca file yang dipilih")
                    }
                    val digest = sha256(selected)
                    scriptBytes?.takeIf { it !== defaultScript }?.fill(0)
                    scriptBytes = selected
                    scriptHash = digest
                    scriptName = uri.lastPathSegment?.substringAfterLast('/') ?: "autoroot.sh"
                    output = if (digest == AUTOROOT_SHA256) {
                        "Skrip cocok dengan revisi upstream yang dipin."
                    } else {
                        "Hash skrip tidak cocok. File ini tidak akan dijalankan."
                    }
                } catch (e: Exception) {
                    error = e.message ?: "Gagal membuka skrip"
                }
            }
        }
    }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: kotlin.error("Tidak dapat membaca private key")
                    }
                    require(bytes.size <= 128 * 1024) { "File key terlalu besar; pilih private key PEM/OpenSSH." }
                    val text = bytes.toString(Charsets.UTF_8)
                    require(text.contains("PRIVATE KEY-----")) { "Format tidak didukung. Pilih private key PEM/OpenSSH, bukan file .pub atau .ppk." }
                    privateKey?.fill(0)
                    privateKey = bytes
                    privateKeyName = uri.lastPathSegment?.substringAfterLast('/') ?: "private key"
                } catch (e: Exception) {
                    error = e.message ?: "Gagal membuka private key"
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("LG webOS — faultmanager autoroot", style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Peringatan penting", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text("Fitur ini mengirim skrip pihak ketiga melalui SSH dan menjalankan eksploit yang mengubah perangkat TV secara persisten. Gunakan hanya pada TV milik Anda atau yang Anda berwenang kelola.")
                Text("Periksa kerentanan firmware TV terlebih dahulu di CanI.RootMy.TV. Firmware terbaru sebagian besar telah dipatch. Jangan reboot setelah berhasil sebelum menghapus aplikasi LG Developer Mode; jangan pasang Developer Mode lagi selama TV rooted.")
                Text("autoroot.sh dari faultmanager-autoroot disertakan sebagai skrip default pinned. Skrip lain boleh dipilih secara opsional, tetapi hanya revisi yang sama persis dan cocok SHA-256 yang dapat dijalankan. Lisensi AGPL-3.0-or-later disertakan bersama asset.")
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AUTOROOT_URL)))
                }) { Text("Buka repositori dan instruksi resmi") }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AUTOROOT_FILE_URL)))
                }) { Text("Unduh skrip resmi versi pinned") }
                Text("Skrip harus persis sama dengan commit a972c95f4f4dbacdb72215409ea0c9eff39dabc1.")
                Text("SHA-256 yang diharapkan: $AUTOROOT_SHA256", style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedTextField(
            host,
            {
                if (host != it) {
                    privateKey?.fill(0)
                    privateKey = null
                    privateKeyName = null
                    keyPassphrase = ""
                }
                host = it
            },
            label = { Text("Alamat IP TV") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port SSH") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Text("Aktifkan Developer Mode dan Key Server di TV. Tekan tombol untuk mengambil key otomatis dari http://IP_TV:9991/webos_rsa. User/port SSH default: prisoner:9922.")
        Button(onClick = { showKeyFetchConfirmation = true }, enabled = !busy && host.isNotBlank()) {
            Text(privateKeyName?.let { "Ambil ulang key · saat ini: $it" } ?: "Ambil private key otomatis dari TV")
        }
        TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text("Atau pilih file private key secara manual (opsional)")
        }
        OutlinedTextField(keyPassphrase, { keyPassphrase = it }, label = { Text("Passphrase private key (jika diminta)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("Private key dan passphrase hanya digunakan untuk koneksi ini; tidak disimpan oleh fitur.", style = MaterialTheme.typography.bodySmall)

        Text("Skrip aktif: $scriptName", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scriptPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text("Pilih autoroot.sh lain (opsional)")
            }
            TextButton(
                onClick = {
                    scriptBytes?.takeIf { it !== defaultScript }?.fill(0)
                    scriptBytes = defaultScript
                    scriptHash = sha256(defaultScript)
                    scriptName = "autoroot.sh (bawaan)"
                },
                enabled = !busy && scriptBytes !== defaultScript,
            ) {
                Text("Gunakan bawaan")
            }
        }
        Text(
            when {
                scriptHash == null -> "Skrip belum tersedia."
                scriptHash == AUTOROOT_SHA256 -> "Hash cocok — skrip tervalidasi untuk revisi pinned."
                else -> "Hash tidak cocok — eksekusi dinonaktifkan."
            },
            color = if (scriptHash != null && scriptHash != AUTOROOT_SHA256) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = debug, onCheckedChange = { debug = it }, enabled = !busy)
            Text("Aktifkan log debug")
        }
        Button(
            onClick = { showRunConfirmation = true },
            enabled = !busy && host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 && username.isNotBlank() && privateKey != null && scriptHash == AUTOROOT_SHA256,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Menjalankan…" else "Sambungkan, unggah & jalankan") }

        if (busy) Text("Koneksi sedang berjalan. Jangan tutup aplikasi atau matikan TV.")
        if (output.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Text(output, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showKeyFetchConfirmation) {
        AlertDialog(
            onDismissRequest = { showKeyFetchConfirmation = false },
            title = { Text("Ambil Developer Mode private key?") },
            text = {
                Text(
                    "Dioxamine akan meminta http://${host.trim()}:9991/webos_rsa melalui HTTP tanpa enkripsi. Private key dapat terlihat oleh perangkat lain di jaringan Wi-Fi/LAN. Lanjutkan hanya pada jaringan tepercaya dan saat TV milik Anda menyalakan Key Server. Key hanya disimpan sementara di memori aplikasi.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showKeyFetchConfirmation = false
                    busy = true
                    output = "Mengambil key dari TV melalui port 9991…\n"
                    scope.launch {
                        try {
                            val fetched = withContext(Dispatchers.IO) { fetchWebOsPrivateKey(host.trim()) }
                            privateKey?.fill(0)
                            privateKey = fetched
                            privateKeyName = "key dari TV (${host.trim()})"
                            output += "Private key berhasil diambil dan belum ditulis ke penyimpanan. Masukkan passphrase Developer Mode jika diminta.\n"
                        } catch (e: Exception) {
                            error = e.message ?: "Gagal mengambil private key dari TV"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Ambil key") }
            },
            dismissButton = { TextButton(onClick = { showKeyFetchConfirmation = false }) { Text("Batal") } },
        )
    }

    if (showRunConfirmation) {
        AlertDialog(
            onDismissRequest = { showRunConfirmation = false },
            title = { Text("Konfirmasi perubahan persisten") },
            text = { Text("Dioxamine akan menerima host-key SSH, mengunggah skrip ${AUTOROOT_SHA256.take(12)}… ke $host:$port, lalu menjalankan eksploit faultmanager sebagai ${username}. Tindakan ini dapat memberi root persisten pada TV dan mengubah instalasi Developer Mode. Lanjutkan hanya jika Anda memahami risiko dan telah memverifikasi TV/firmware.") },
            confirmButton = { TextButton(onClick = {
                showRunConfirmation = false
                val payload = scriptBytes ?: return@TextButton
                val identity = privateKey ?: return@TextButton
                busy = true
                output = "Menghubungkan ke $host:$port…\n"
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            uploadAndRun(
                                host = host.trim(), port = port.toInt(), username = username.trim(),
                                privateKey = identity, keyPassphrase = keyPassphrase,
                                script = payload, debug = debug,
                                knownHosts = File(context.filesDir, "webos_known_hosts"),
                                confirmHostKey = { message ->
                                    suspendCancellableCoroutine { continuation ->
                                        Handler(Looper.getMainLooper()).post {
                                            pendingHostKey = message
                                            hostKeyDecision = { accepted ->
                                                if (continuation.isActive) continuation.resume(accepted)
                                                pendingHostKey = null
                                                hostKeyDecision = null
                                            }
                                        }
                                    }
                                },
                                onOutput = { line -> withContext(Dispatchers.Main) { output += line } },
                            )
                        }
                    } catch (e: Exception) {
                        error = e.message ?: "Koneksi SSH gagal"
                    } finally {
                        busy = false
                        identity.fill(0)
                        privateKey = null
                        privateKeyName = null
                        keyPassphrase = ""
                    }
                }
            }) { Text("Saya paham, lanjutkan") } },
            dismissButton = { TextButton(onClick = { showRunConfirmation = false }) { Text("Batal") } },
        )
    }

    pendingHostKey?.let { fingerprintPrompt ->
        AlertDialog(
            onDismissRequest = { hostKeyDecision?.invoke(false) },
            title = { Text("Verifikasi host-key SSH") },
            text = { Text("Server menyajikan identitas berikut:\n\n$fingerprintPrompt\n\nBandingkan fingerprint dengan TV/jaringan tepercaya sebelum menyetujuinya. Jika tidak dapat diverifikasi, batalkan koneksi. Host-key yang disetujui akan disimpan untuk pemeriksaan koneksi berikutnya.") },
            confirmButton = { TextButton(onClick = { hostKeyDecision?.invoke(true) }) { Text("Terima host-key") } },
            dismissButton = { TextButton(onClick = { hostKeyDecision?.invoke(false) }) { Text("Tolak") } },
        )
    }

    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Kesalahan") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    }
}

private suspend fun uploadAndRun(
    host: String,
    port: Int,
    username: String,
    privateKey: ByteArray,
    keyPassphrase: String,
    script: ByteArray,
    debug: Boolean,
    knownHosts: File,
    confirmHostKey: suspend (String) -> Boolean,
    onOutput: suspend (String) -> Unit,
) {
    val jsch = JSch()
    jsch.addIdentity("webos-tv", privateKey, null, keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray())
    knownHosts.parentFile?.mkdirs()
    if (knownHosts.exists()) jsch.setKnownHosts(knownHosts.absolutePath)
    val session = jsch.getSession(username, host, port)
    session.setConfig("StrictHostKeyChecking", "ask")
    session.setUserInfo(object : UserInfo {
        override fun getPassphrase(): String? = keyPassphrase.takeIf { it.isNotEmpty() }
        override fun getPassword(): String? = null
        override fun promptPassword(message: String): Boolean = false
        override fun promptPassphrase(message: String): Boolean = false
        override fun promptYesNo(message: String): Boolean = runBlocking { confirmHostKey(message) }
        override fun showMessage(message: String) = Unit
    })
    var sftp: ChannelSftp? = null
    var exec: ChannelExec? = null
    try {
        session.connect(20_000)
        withContext(Dispatchers.Main) { onOutput("SSH terverifikasi. Mengunggah skrip ke $REMOTE_SCRIPT…\n") }
        sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(15_000)
        sftp.put(script.inputStream(), REMOTE_SCRIPT)
        sftp.disconnect()
        sftp = null

        val command = "sh $REMOTE_SCRIPT" + if (debug) " --debug" else ""
        exec = session.openChannel("exec") as ChannelExec
        exec.setCommand(command)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        exec.setOutputStream(stdout)
        exec.setErrStream(stderr)
        exec.connect(15_000)
        var outPos = 0
        var errPos = 0
        val started = System.currentTimeMillis()
        while (!exec.isClosed) {
            if (System.currentTimeMillis() - started > 20 * 60 * 1000L) {
                exec.disconnect()
                error("Batas waktu 20 menit tercapai; status eksploit pada TV perlu diperiksa secara manual.")
            }
            val outText = stdout.toString("UTF-8")
            val errText = stderr.toString("UTF-8")
            if (outText.length > outPos) {
                onOutput(outText.substring(outPos))
                outPos = outText.length
            }
            if (errText.length > errPos) {
                onOutput(errText.substring(errPos).let { "[stderr] $it" })
                errPos = errText.length
            }
            kotlinx.coroutines.delay(250)
        }
        val outText = stdout.toString("UTF-8")
        val errText = stderr.toString("UTF-8")
        if (outText.length > outPos) onOutput(outText.substring(outPos))
        if (errText.length > errPos) onOutput("[stderr] ${errText.substring(errPos)}")
        val exitStatus = exec.exitStatus
        if (exitStatus == 0) onOutput("\nSkrip selesai dengan exit code 0. Ikuti instruksi resmi sebelum reboot.\n")
        else error("Skrip berakhir dengan exit code $exitStatus. Periksa log TV dan panduan resmi.")
    } finally {
        try { sftp?.disconnect() } catch (_: Exception) { }
        try { exec?.disconnect() } catch (_: Exception) { }
        session.disconnect()
        jsch.removeAllIdentity()
        privateKey.fill(0)
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun fetchWebOsPrivateKey(ipInput: String): ByteArray {
    val ip = ipInput.trim().removePrefix("[").removeSuffix("]")
    require(ip.matches(Regex("[0-9a-fA-F:.]+")) && (ip.contains('.') || ip.contains(':'))) {
        "Masukkan IP literal TV, bukan hostname atau URL."
    }
    val address = InetAddress.getByName(ip)
    val isPrivateLanAddress = when (address) {
        is Inet4Address -> address.isSiteLocalAddress
        is Inet6Address -> address.isLinkLocalAddress ||
            (address.address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }
    require(isPrivateLanAddress && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress) {
        "Key hanya dapat diambil dari alamat IP privat/link-local TV di jaringan lokal."
    }

    val response = ByteArrayOutputStream()
    Socket().use { socket ->
        socket.connect(InetSocketAddress(address, 9991), 5_000)
        socket.soTimeout = 8_000
        val hostHeader = if (address is Inet6Address) "[${address.hostAddress.substringBefore('%')}]" else address.hostAddress
        socket.getOutputStream().write(
            "GET /webos_rsa HTTP/1.1\r\nHost: $hostHeader:9991\r\nAccept: application/octet-stream, text/plain\r\nConnection: close\r\nUser-Agent: Dioxamine-webOS\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        val buffer = ByteArray(4096)
        while (true) {
            val count = socket.getInputStream().read(buffer)
            if (count < 0) break
            require(response.size() + count <= 160 * 1024) { "Respons key terlalu besar; koneksi dihentikan." }
            response.write(buffer, 0, count)
        }
    }

    val raw = response.toByteArray()
    val separator = byteArrayOf(13, 10, 13, 10)
    val headerEnd = raw.indexOfSequence(separator)
    require(headerEnd in 1..8192) { "Respons Key Server tidak valid atau header terlalu besar." }
    val headerText = raw.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
    val status = headerText.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1
    require(status == 200) { "Key Server mengembalikan HTTP $status; pastikan Key Server aktif di TV." }
    val headers = headerText.lineSequence().drop(1).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else line.substring(0, colon).trim().lowercase(Locale.US) to line.substring(colon + 1).trim()
    }.toMap()
    var body = raw.copyOfRange(headerEnd + separator.size, raw.size)
    if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
        body = decodeChunkedBody(body)
    } else {
        val expectedLength = headers["content-length"]?.toIntOrNull()
        if (expectedLength != null) {
            require(expectedLength in 1..128 * 1024 && body.size >= expectedLength) { "Respons key terpotong atau ukurannya tidak valid." }
            body = body.copyOf(expectedLength)
        }
    }
    require(body.isNotEmpty() && body.size <= 128 * 1024) { "Key Server tidak mengirim private key yang valid." }
    val text = body.toString(Charsets.UTF_8).trim()
    require(text.contains("PRIVATE KEY-----") && !text.trimStart().startsWith("<")) {
        "Respons bukan private key PEM/OpenSSH. Pastikan alamat TV dan endpoint Key Server benar."
    }
    return text.toByteArray(Charsets.UTF_8)
}

private fun ByteArray.indexOfSequence(sequence: ByteArray): Int {
    if (sequence.isEmpty() || size < sequence.size) return -1
    for (start in 0..(size - sequence.size)) {
        var match = true
        for (offset in sequence.indices) {
            if (this[start + offset] != sequence[offset]) {
                match = false
                break
            }
        }
        if (match) return start
    }
    return -1
}

private fun decodeChunkedBody(chunked: ByteArray): ByteArray {
    val input = ByteArrayInputStream(chunked)
    val output = ByteArrayOutputStream()
    fun readLine(): String {
        val line = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            require(current >= 0) { "Respons chunked terpotong." }
            if (previous == 13 && current == 10) {
                val bytes = line.toByteArray()
                return bytes.copyOf(maxOf(0, bytes.size - 1)).toString(Charsets.US_ASCII)
            }
            line.write(current)
            previous = current
            require(line.size() <= 128) { "Header chunked tidak valid." }
        }
    }
    while (true) {
        val size = readLine().substringBefore(';').trim().toIntOrNull(16)
            ?: error("Ukuran chunk HTTP tidak valid.")
        if (size == 0) break
        require(size > 0 && output.size() + size <= 128 * 1024) { "Body key melebihi batas ukuran." }
        val chunk = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(chunk, offset, size - offset)
            require(count > 0) { "Chunk HTTP terpotong." }
            offset += count
        }
        output.write(chunk)
        require(input.read() == 13 && input.read() == 10) { "Delimiter chunk HTTP tidak valid." }
    }
    return output.toByteArray()
}
