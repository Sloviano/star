package com.starlink.scanner.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starlink.scanner.data.update.UpdateStatus

/**
 * "Update available" prompt shown at launch (or from Settings ▸ Check for updates now). While the
 * accepted APK downloads, the notes are replaced by a progress bar (determinate with a percentage
 * when the size is known, indeterminate otherwise) and the dialog is non-dismissible with no buttons.
 *
 * @param progress 0..100, or -1 when the total download size is unknown.
 */
@Composable
fun UpdateDialog(
    available: UpdateStatus.Available,
    downloading: Boolean,
    progress: Int,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!downloading) onLater() },
        title = { Text("Update available — ${available.versionName}") },
        text = {
            if (downloading) {
                Column {
                    Text(
                        if (progress in 0..100) "Downloading… $progress%" else "Downloading…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (progress in 0..100) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        available.notes.ifBlank { "A newer build is ready to install." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (!downloading) TextButton(onClick = onUpdate) { Text("Update") }
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
