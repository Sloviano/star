package com.starlink.scanner.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starlink.scanner.ui.theme.LocalAppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onCheckForUpdates: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val savedUrl by viewModel.sheetsUrl.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val lastUpload by viewModel.lastUploadTime.collectAsStateWithLifecycle()
    val lastError by viewModel.lastUploadError.collectAsStateWithLifecycle()
    val autoUpdateCheck by viewModel.autoUpdateCheck.collectAsStateWithLifecycle()
    val lastUpdateCheck by viewModel.lastUpdateCheck.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(savedUrl) }

    // Seed the editable field once the persisted value arrives.
    LaunchedEffect(savedUrl) { url = savedUrl }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Apps Script URL", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            placeholder = { Text("https://script.google.com/macros/s/…/exec") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.setSheetsUrl(url.trim()) },
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
        ) { Text("Save URL") }
        OutlinedButton(
            onClick = { viewModel.setSheetsUrl(url.trim()); viewModel.testConnection() },
            enabled = testResult !is TestResult.Testing,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
        ) { Text(if (testResult is TestResult.Testing) "Testing…" else "Test connection") }

        TestResultLine(testResult)

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        DiagnosticRow("Not yet uploaded", if (pendingCount == 0) "All synced" else "$pendingCount record(s)")
        DiagnosticRow("Last successful upload", formatLastUpload(lastUpload))
        if (lastError.isNotBlank()) {
            DiagnosticRow("Last upload error", lastError, valueColor = LocalAppColors.current.error)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Check for updates on launch", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = autoUpdateCheck, onCheckedChange = { viewModel.setAutoUpdateCheck(it) })
        }
        DiagnosticRow("Installed version", viewModel.currentVersion)
        DiagnosticRow("Last checked", formatLastUpload(lastUpdateCheck))
        OutlinedButton(
            onClick = onCheckForUpdates,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
        ) { Text("Check for updates now") }

        Spacer(Modifier.height(32.dp))
        Text(
            "The Starlink local API is unofficial. Upload sends records to your own Google Sheet " +
                "via the Apps Script Web App configured above.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TestResultLine(result: TestResult?) {
    val colors = LocalAppColors.current
    val (text, color: Color) = when (result) {
        null -> return
        is TestResult.Testing -> return
        is TestResult.Ok -> result.message to colors.success
        is TestResult.Error -> result.message to colors.error
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DiagnosticRow(label: String, value: String, valueColor: Color? = null) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun formatLastUpload(epochMs: Long): String =
    if (epochMs <= 0L) "Never"
    else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
