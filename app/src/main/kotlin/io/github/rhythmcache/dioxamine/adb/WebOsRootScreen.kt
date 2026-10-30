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
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

private const val AUTOROOT_URL = "https://github.com/throwaway96/faultmanager-autoroot"
private const val AUTOROOT_FILE_URL = "https://raw.githubusercontent.com/throwaway96/faultmanager-autoroot/a972c95f4f4dbacdb72215409ea0c9eff39dabc1/autoroot.sh"
private const val AUTOROOT_SHA256 = "66a8a2816567a3c3d29768095873d347ec6bb2cbf60208979d15d7a42525d4d0"
private const val REMOTE_SCRIPT = "/tmp/autoroot.sh"

@Composable
fun WebOsRootScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9922") }
    var username by remember { mutableStateOf("prisoner") }
    var keyPassphrase by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf<ByteArray?>(null) }
    var privateKeyName by remember { mutableStateOf<String?>(null) }
    var scriptBytes by remember { mutableStateOf<ByteArray?>(null) }
    var scriptHash by remember { mutableStateOf<String?>(null) }
    var debug by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingHostKey by remember { mutableStateOf<String?>(null) }
    var hostKeyDecision by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var showRunConfirmation by remember { mutableStateOf(false) }

    val scriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: kotlin.error("Tidak dapat membaca file yang dipilih")
                    }
                    val digest = sha256(selected)
                    scriptBytes = selected
                    scriptHash = digest
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
                Text("Proyek faultmanager-autoroot berlisensi AGPL-3.0-or-later. Skrip tidak dibundel dengan Dioxamine; pilih sendiri file dari repositori resmi dan hash harus sama dengan revisi yang ditinjau.")
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

        OutlinedTextField(host, { host = it }, label = { Text("Alamat IP / hostname TV") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port SSH") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Text("LG Developer Mode memakai SSH key, bukan kata sandi akun. Aktifkan Key Server, ambil private key melalui ares-novacom --getkey, lalu pilih file private key di bawah. User/port default: prisoner:9922.")
        Button(onClick = { keyPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text(privateKeyName?.let { "Private key: $it" } ?: "Pilih private key webOS")
        }
        OutlinedTextField(keyPassphrase, { keyPassphrase = it }, label = { Text("Passphrase private key (jika diminta)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("Private key dan passphrase hanya digunakan untuk koneksi ini; tidak disimpan oleh fitur.", style = MaterialTheme.typography.bodySmall)

        Button(onClick = { scriptPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text(if (scriptHash == null) "Pilih autoroot.sh" else "Pilih ulang autoroot.sh")
        }
        Text(
            when {
                scriptHash == null -> "Belum ada skrip dipilih. Unduh revisi yang ditinjau dari repositori resmi."
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
