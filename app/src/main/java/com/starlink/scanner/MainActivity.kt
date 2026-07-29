package com.starlink.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.starlink.scanner.ui.navigation.AppNavigation
import com.starlink.scanner.ui.theme.StarlinkScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StarlinkScannerTheme {
                AppNavigation()
            }
        }
    }
}
