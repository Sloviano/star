package com.starlink.scanner.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.domain.UploadStatus
import com.starlink.scanner.ui.theme.LocalAppColors
import com.starlink.scanner.ui.theme.MonoValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.messages.collect(onMessage)
    }

    if (records.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No scans yet — capture your first kit.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        SyncNowBar(isSyncing = isSyncing, onSync = viewModel::syncNow)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(records, key = { it.id }) { record -> HistoryRow(record) }
        }
    }
}

@Composable
private fun SyncNowBar(isSyncing: Boolean, onSync: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        OutlinedButton(onClick = onSync, enabled = !isSyncing) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null)
            }
            Text("  Sync now")
        }
    }
}

@Composable
private fun HistoryRow(record: ScanRecord) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            StatusChip(record.status)
            Text(
                // Records saved before the counter existed carry 0 and show the kit number alone.
                if (record.counter > 0) "#${record.counter}  ${record.kitNumber}" else record.kitNumber,
                style = MonoValue,
                fontWeight = FontWeight.Bold,
            )
            Text(
                record.dishId,
                style = MonoValue.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                relativeTime(record.timestamp) +
                    if (record.status == UploadStatus.FAILED) "  ·  ${record.attempts} attempts" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusChip(status: UploadStatus) {
    val colors = LocalAppColors.current
    val color: Color = when (status) {
        UploadStatus.PENDING -> colors.pending
        UploadStatus.SENT -> colors.success
        UploadStatus.FAILED -> colors.error
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            status.name,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
