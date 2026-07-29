package com.starlink.scanner.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starlink.scanner.data.update.UpdateStatus

/**
 * "Update available" prompt shown at launch (or from Settings ▸ Check for updates now). While the
 * accepted APK downloads, buttons are replaced by a spinner and the dialog is non-dismissible.
 */
@Composable
fun UpdateDialog(
    available: UpdateStatus.Available,
    downloading: Boolean,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!downloading) onLater() },
        title = { Text("Update available — ${available.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                if (available.notes.isNotBlank()) {
                    Text(available.notes, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("A newer build is ready to install.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (downloading) {
                CircularProgressIndicator(
                    Modifier.padding(end = 12.dp).size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(onClick = onUpdate) { Text("Update") }
            }
        },
        dismissButton = {
            if (!downloading) {
                Column {
                    TextButton(onClick = onLater) { Text("Later") }
                    TextButton(onClick = onSkip) { Text("Skip this version") }
                }
            }
        },
    )
}
