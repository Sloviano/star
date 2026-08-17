package com.starlink.scanner.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starlink.scanner.domain.BarcodeFormat
import com.starlink.scanner.domain.ScanTarget
import com.starlink.scanner.ui.theme.LocalAppColors
import com.starlink.scanner.ui.theme.MonoValue

private val ButtonHeight = 56.dp

@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
    viewModel: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var torchEnabled by remember { mutableStateOf(false) }

    // Torch is meaningless once the camera unbinds; reset it whenever we leave capture.
    val capturing = state is CaptureUiState.Capturing
    LaunchedEffect(capturing) { if (!capturing) torchEnabled = false }

    // Beep + vibrate once per accepted new signalizer fill (scan or dish-ID connect).
    val haptics = LocalHapticFeedback.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(Unit) {
        viewModel.scanEvents.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                // Capture is full-bleed (camera preview edge-to-edge) — no scroll/padding.
                is CaptureUiState.Capturing -> CapturingContent(
                    s = s,
                    onScan = viewModel::onScan,
                    onConfirmScan = viewModel::onConfirmScan,
                    onRejectScan = viewModel::onRejectScan,
                    onSelectTarget = viewModel::onSelectTarget,
                    onConnectStarlink = viewModel::onConnectStarlink,
                    canConnect = viewModel.canConnectStarlink,
                    torchEnabled = torchEnabled,
                    onToggleTorch = { torchEnabled = !torchEnabled },
                )

                is CaptureUiState.Summary -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    SummaryContent(s)
                }
            }
        }

        // Bottom action area — one large primary action per state.
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (val s = state) {
                is CaptureUiState.Capturing -> Button(
                    onClick = viewModel::onReview,
                    enabled = s.canSave,
                    modifier = Modifier.fillMaxWidth().height(ButtonHeight),
                ) { Text(if (s.canSave) "Save" else "Waiting for kit, dish serial & ID…") }

                is CaptureUiState.Summary -> {
                    Button(
                        onClick = { viewModel.onSave(onSaved) },
                        modifier = Modifier.fillMaxWidth().height(ButtonHeight),
                    ) { Text("Save to Google Sheet") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = viewModel::onDiscard,
                        modifier = Modifier.fillMaxWidth().height(ButtonHeight),
                    ) { Text("Discard") }
                }
            }
        }
    }
}

@Composable
private fun CapturingContent(
    s: CaptureUiState.Capturing,
    onScan: (String, BarcodeFormat) -> Unit,
    onConfirmScan: () -> Unit,
    onRejectScan: () -> Unit,
    onSelectTarget: (ScanTarget) -> Unit,
    onConnectStarlink: () -> Unit,
    canConnect: Boolean,
    torchEnabled: Boolean,
    onToggleTorch: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    if (!hasCameraPermission) {
        CameraRationale(onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        return
    }

    // Keep the screen awake while the technician lines up barcodes.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        // Camera region = the preview pane plus the shortening gap below it. The reticle is centered
        // over this whole region so it sits in the middle of the *visible* preview (the preview fills
        // down through the gap), not just the logical pane.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CameraScanner(
                        onBarcode = onScan,
                        torchEnabled = torchEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Shorten the camera pane by this fixed gap (the pane is weight(1f), so this comes
                // out of its height).
                Spacer(Modifier.height(180.dp))
            }

            // Center reticle to aim the barcode — centered over the full camera region.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 200.dp, height = 150.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            )

            // Torch, thumb-reachable at the top-right.
            FilledIconButton(
                onClick = onToggleTorch,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(
                    if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (torchEnabled) "Turn torch off" else "Turn torch on",
                )
            }
        }

        // The three signalizers below the camera — kit, dish serial, dish ID. They wrap to content
        // and sit just above the Save button.
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Kit — scanned (guided). Tap to re-select for the next scan.
            SignalizerRow(
                label = "Kit number",
                value = s.checklist.kitNumber,
                active = s.target == ScanTarget.KIT,
                pendingText = if (s.target == ScanTarget.KIT) "scanning…" else "waiting to scan",
                onClick = { onSelectTarget(ScanTarget.KIT) },
            )
            // Dish serial — scanned (guided).
            SignalizerRow(
                label = "Dish serial",
                value = s.checklist.dishSerial,
                active = s.target == ScanTarget.DISH,
                pendingText = if (s.target == ScanTarget.DISH) "scanning…" else "waiting to scan",
                onClick = { onSelectTarget(ScanTarget.DISH) },
            )
            // Dish ID — read automatically once connected. While disconnected, tapping the row does a
            // one-tap connect to the dish's "STARLINK…" AP (or opens WiFi settings on older devices).
            SignalizerRow(
                label = "Dish ID",
                value = s.dishId,
                active = false,
                pendingText = connectionText(s.phase, s.attempts, canConnect),
                connecting = s.phase == CaptureUiState.SearchPhase.CONNECTING,
                onClick = when {
                    s.dishId != null -> null
                    canConnect -> onConnectStarlink
                    else -> { { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
                },
            )
        }
    }

    // Confirm a just-scanned code before it fills the field, so a mis-aimed scan can be rejected.
    s.pendingScan?.let { pending ->
        ScanConfirmDialog(pending = pending, onAccept = onConfirmScan, onReject = onRejectScan)
    }
}

/**
 * Accept/No confirmation for a scanned kit or dish-serial code. The value is shown in the same
 * monospace as elsewhere so long alphanumerics are verifiable before committing.
 */
@Composable
private fun ScanConfirmDialog(
    pending: CaptureUiState.PendingScan,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val fieldName = when (pending.target) {
        ScanTarget.KIT -> "kit number"
        ScanTarget.DISH -> "dish serial"
    }
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Confirm $fieldName") },
        text = {
            Column {
                Text("Scanned value:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    pending.value,
                    style = MonoValue.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Is this the right code? Tap No to re-scan if it grabbed the wrong label.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Accept") } },
        dismissButton = { OutlinedButton(onClick = onReject) { Text("No") } },
    )
}

/** Human-readable state for the dish-ID signalizer while it's still connecting. */
private fun connectionText(
    phase: CaptureUiState.SearchPhase,
    attempts: Int,
    canConnect: Boolean,
): String = when (phase) {
    CaptureUiState.SearchPhase.NO_WIFI ->
        if (canConnect) "Tap to connect to Starlink" else "No WiFi — tap to open settings"
    CaptureUiState.SearchPhase.NO_DISH ->
        if (canConnect) "Tap to connect to Starlink" else "No Starlink dish on this WiFi"
    CaptureUiState.SearchPhase.CONNECTING -> "Connecting… attempt $attempts"
    CaptureUiState.SearchPhase.CONNECTED -> "connected"
}

/**
 * One of the three readiness signalizers overlaid on the camera. Green + check once [value] is
 * captured; otherwise shows [pendingText] (and a spinner when [connecting]). [active] draws a bright
 * outline for the field the next scan will fill.
 */
@Composable
private fun SignalizerRow(
    label: String,
    value: String?,
    active: Boolean,
    pendingText: String,
    connecting: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val ready = !value.isNullOrBlank()
    val colors = LocalAppColors.current
    val container = if (ready) colors.success else MaterialTheme.colorScheme.surfaceVariant
    val content = if (ready) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (active) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
    val shape = RoundedCornerShape(10.dp)

    // Use a plain (non-clickable) Surface when there's no action, so the ready/green state is never
    // subject to a disabled clickable-Surface's dimming — that's what greyed out the connected Dish ID.
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            color = container,
            contentColor = content,
            shape = shape,
            border = border,
        ) { SignalizerRowBody(ready, connecting, label, value, pendingText, content) }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = container,
            contentColor = content,
            shape = shape,
            border = border,
        ) { SignalizerRowBody(ready, connecting, label, value, pendingText, content) }
    }
}

@Composable
private fun SignalizerRowBody(
    ready: Boolean,
    connecting: Boolean,
    label: String,
    value: String?,
    pendingText: String,
    content: Color,
) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            ready -> Icon(Icons.Default.Check, contentDescription = "ready", modifier = Modifier.size(20.dp))
            connecting -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            else -> Icon(
                Icons.Default.WifiFind,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = content.copy(alpha = 0.4f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium)
            Text(
                value ?: pendingText,
                style = MonoValue.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CameraRationale(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Camera access needed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "The scanner uses the camera to read the kit box barcodes. Nothing is saved until you tap Save.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, modifier = Modifier.height(ButtonHeight)) {
            Text("Grant camera access")
        }
    }
}

@Composable
private fun SummaryContent(s: CaptureUiState.Summary) {
    Column {
        Text("Confirm", style = MaterialTheme.typography.headlineSmall)
        if (s.duplicate) {
            Spacer(Modifier.height(12.dp))
            Card {
                Text(
                    "This kit was already recorded.",
                    color = LocalAppColors.current.pending,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                DishIdField(s.record.dishId)
                Spacer(Modifier.height(12.dp))
                LabelValue("Counter", "#${s.record.counter}")
                LabelValue("Kit number", s.record.kitNumber)
                LabelValue("Dish serial", s.record.dishSerial)
            }
        }
    }
}

@Composable
private fun DishIdField(dishId: String) {
    Column {
        Text("DISH ID", style = MaterialTheme.typography.labelLarge)
        Text(dishId, style = MonoValue)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge)
        Text(value, style = MonoValue.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
    }
}
