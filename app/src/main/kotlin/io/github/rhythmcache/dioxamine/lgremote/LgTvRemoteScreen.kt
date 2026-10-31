package io.github.rhythmcache.dioxamine.lgremote

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.rhythmcache.dioxamine.R

@Composable
internal fun LgTvRemoteScreen(vm: LgTvRemoteViewModel) {
    val context = LocalContext.current
    var manualIp by rememberSaveable { mutableStateOf("") }
    var showForgetDialog by remember { mutableStateOf(false) }
    val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.discover()
    }

    fun hasLanPermission(): Boolean {
        val targetSdk = context.applicationInfo.targetSdkVersion
        return if (Build.VERSION.SDK_INT >= 37 && targetSdk >= 37) {
            ContextCompat.checkSelfPermission(context, localNetworkPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun scan() {
        if (Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37 && !hasLanPermission()) {
            permissionLauncher.launch(localNetworkPermission)
        } else {
            vm.discover()
        }
    }

    LaunchedEffect(Unit) {
        // Discovery starts automatically when LAN access is already granted. On Android 17-targeting
        // builds, request the new runtime permission only after the user taps Scan.
        if (hasLanPermission()) vm.discover()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.lg_remote_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.lg_remote_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = ::scan, enabled = !vm.isScanning) {
                if (vm.isScanning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.lg_remote_scan))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.lg_remote_pairing_title), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(R.string.lg_remote_pairing_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        Text(vm.status, style = MaterialTheme.typography.bodyMedium)
        vm.errorMessage?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (vm.devices.isNotEmpty()) {
            Text(stringResource(R.string.lg_remote_devices), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            vm.devices.forEach { device ->
                val selected = vm.selectedDevice?.host == device.host
                Card(
                    onClick = { vm.selectDevice(device) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            device.server.ifBlank { device.host },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!device.likelyLg) {
                            Text(stringResource(R.string.lg_remote_unknown_renderer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text(stringResource(R.string.lg_remote_ip_label)) },
                placeholder = { Text("192.168.1.25") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (vm.addManualHost(manualIp)) manualIp = "" }) {
                Text(stringResource(R.string.lg_remote_add_ip))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = vm::connect,
                enabled = vm.selectedDevice != null && !vm.isConnecting && !vm.isConnected,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (vm.isConnecting && vm.isAwaitingApproval) stringResource(R.string.lg_remote_waiting) else stringResource(R.string.lg_remote_connect_pair))
            }
            OutlinedButton(
                onClick = { vm.powerAction() },
                enabled = vm.selectedDevice != null,
            ) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = stringResource(R.string.lg_remote_power))
            }
        }

        if (vm.selectedDevice != null && !vm.isConnected && vm.selectedMac != null) {
            Text(stringResource(R.string.lg_remote_wol_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (vm.selectedDevice != null) {
            TextButton(onClick = { showForgetDialog = true }, enabled = !vm.isConnecting) {
                Text(stringResource(R.string.lg_remote_forget_pairing))
            }
        }

        Text(stringResource(R.string.lg_remote_controls), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RemoteKey("▲", "UP", vm.isConnected, vm::pressButton)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteKey("◀", "LEFT", vm.isConnected, vm::pressButton)
                    Button(
                        onClick = { vm.pressButton("ENTER") },
                        enabled = vm.isConnected,
                        modifier = Modifier.size(width = 88.dp, height = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text(stringResource(R.string.lg_remote_ok)) }
                    RemoteKey("▶", "RIGHT", vm.isConnected, vm::pressButton)
                }
                RemoteKey("▼", "DOWN", vm.isConnected, vm::pressButton)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RemoteKey(stringResource(R.string.lg_remote_back), "BACK", vm.isConnected, vm::pressButton, Modifier.weight(1f))
            RemoteKey("HOME", "HOME", vm.isConnected, vm::pressButton, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RemoteKey("VOL −", "VOLUMEDOWN", vm.isConnected, vm::pressButton, Modifier.weight(1f))
            RemoteKey("MUTE", "MUTE", vm.isConnected, vm::pressButton, Modifier.weight(1f))
            RemoteKey("VOL +", "VOLUMEUP", vm.isConnected, vm::pressButton, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RemoteKey("⏮", "REWIND", vm.isConnected, vm::pressButton, Modifier.weight(1f))
            RemoteKey("▶ / Ⅱ", "PLAY", vm.isConnected, vm::pressButton, Modifier.weight(1f))
            RemoteKey("⏭", "FASTFORWARD", vm.isConnected, vm::pressButton, Modifier.weight(1f))
        }

        Text(stringResource(R.string.lg_remote_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text(stringResource(R.string.lg_remote_forget_title)) },
            text = { Text(stringResource(R.string.lg_remote_forget_message)) },
            confirmButton = {
                TextButton(onClick = { vm.forgetPairing(); showForgetDialog = false }) {
                    Text(stringResource(R.string.lg_remote_forget_pairing))
                }
            },
            dismissButton = { TextButton(onClick = { showForgetDialog = false }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

@Composable
private fun RemoteKey(
    label: String,
    key: String,
    enabled: Boolean,
    onPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { onPress(key) },
        enabled = enabled,
        modifier = modifier.height(48.dp),
    ) { Text(label) }
}
