package com.starlink.scanner.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The three top-level, bottom-nav destinations. */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Capture("capture", "Capture", Icons.Filled.QrCodeScanner),
    History("history", "History", Icons.Filled.History),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
