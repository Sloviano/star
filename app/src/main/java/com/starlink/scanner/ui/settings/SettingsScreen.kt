package com.starlink.scanner.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
    val autoJoinDishWifi by viewModel.autoJoinDishWifi.collectAsStateWithLifecycle()
    val autoJoinResult by viewModel.autoJoinResult.collectAsStateWithLifecycle()
    val dishSsid by viewModel.dishSsid.collectAsStateWithLifecycle()
    val nextCounter by viewModel.nextCounter.collectAsStateWithLifecycle()
    val counterResult by viewModel.counterResult.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(savedUrl) }
    var counter by remember { mutableStateOf(nextCounter.toString()) }

    // Seed the editable fields once the persisted values arrive — and, for the counter, whenever it
    // advances after a save, so reopening Settings shows the number the next record will really use.
    LaunchedEffect(savedUrl) { url = savedUrl }
    LaunchedEffect(nextCounter) { counter = nextCounter.toString() }

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

        Text("Row counter", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = counter,
            onValueChange = { counter = it.filter(Char::isDigit) },
            singleLine = true,
            label = { Text("Next number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The first column of each sheet row. Set it to any starting number; every kit you save " +
                "takes the next one and adds 1.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { viewModel.setNextCounter(counter) },
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
        ) { Text("Save counter") }
        TestResultLine(counterResult)

        if (viewModel.canSuggestWifi) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Dish WiFi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("Auto-join dish WiFi", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = autoJoinDishWifi,
                    onCheckedChange = { viewModel.setAutoJoinDishWifi(it) },
                )
            }
            Text(
                "Lets Android join the dish access point for the whole phone, so other apps can " +
                    "reach the dish too. Tapping Dish ID on the Capture screen connects this app " +
                    "only. Android asks you to approve the suggestion the first time it matches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DiagnosticRow("Network to join", dishSsid)
            TestResultLine(autoJoinResult)
        }

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
