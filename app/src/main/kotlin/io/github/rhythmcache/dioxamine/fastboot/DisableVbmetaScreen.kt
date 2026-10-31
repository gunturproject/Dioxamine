package io.github.rhythmcache.dioxamine.fastboot

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.fastboot.FastbootFileUtils.formatBytes
import io.github.rhythmcache.dioxamine.fastboot.FastbootFileUtils.resolveNameAndSize

private data class SelectedVbmeta(val uri: android.net.Uri, val name: String, val size: Long)

@Composable
fun DisableVbmetaScreen(vm: FastbootViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<SelectedVbmeta?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (name, size) = resolveNameAndSize(context, uri)
            selected = SelectedVbmeta(uri, name, size)
            acknowledged = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        FastbootDetailHeader(
            title = stringResource(R.string.fastboot_vbmeta_title),
            onBack = onBack,
        )
        Spacer(Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.fastboot_vbmeta_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.fastboot_vbmeta_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.fastboot_vbmeta_desc), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !vm.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.fastboot_vbmeta_choose))
        }

        selected?.let { file ->
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(file.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.fastboot_vbmeta_validation), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    acknowledged = false
                    showConfirm = true
                },
                enabled = vm.isConnected && !vm.isBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.fastboot_vbmeta_flash_button))
            }
        }

        if (vm.isBusy) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(vm.currentOperation ?: stringResource(R.string.fastboot_working_placeholder), style = MaterialTheme.typography.bodySmall)
        } else if (vm.lastOperationStatus !is OperationStatus.Idle) {
            Spacer(Modifier.height(16.dp))
            OperationStatusCard(status = vm.lastOperationStatus, onDismiss = { vm.clearOperationStatus() })
        }
    }

    val file = selected
    if (showConfirm && file != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.fastboot_vbmeta_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.fastboot_vbmeta_confirm_message, file.name))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.fastboot_vbmeta_acknowledge), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        vm.flashVbmetaWithDisabledAvb(context, file.uri, file.name)
                    },
                    enabled = acknowledged && vm.isConnected && !vm.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.fastboot_vbmeta_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.fastboot_btn_cancel))
                }
            },
        )
    }
}
