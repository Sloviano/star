package com.starlink.scanner.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.starlink.scanner.di.ServiceLocator
import com.starlink.scanner.ui.SessionState
import com.starlink.scanner.ui.capture.CaptureScreen
import com.starlink.scanner.ui.components.StatusStrip
import com.starlink.scanner.ui.history.HistoryScreen
import com.starlink.scanner.ui.settings.SettingsScreen
import com.starlink.scanner.ui.update.UpdateDialog
import com.starlink.scanner.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val dishConnection by SessionState.dishConnection.collectAsStateWithLifecycle()
    val pendingCount by remember { ServiceLocator.scanDao.pendingCount() }
        .collectAsState(initial = 0)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // In-app updater (Module 6): check on launch, surface the prompt over any screen.
    val updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory)
    val updatePrompt by updateViewModel.prompt.collectAsStateWithLifecycle()
    val updateDownloading by updateViewModel.downloading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        updateViewModel.checkOnLaunch()
        updateViewModel.messages.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    val current = Destination.entries.firstOrNull { it.route == currentRoute } ?: Destination.Capture

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(current.label) })
                StatusStrip(dishConnection = dishConnection, pendingCount = pendingCount)
            }
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { dest ->
                    val selected = backStackEntry?.destination?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Capture.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Capture.route) {
                CaptureScreen(onSaved = {
                    scope.launch { snackbarHostState.showSnackbar("Saved ✓") }
                })
            }
            composable(Destination.History.route) {
                HistoryScreen(onMessage = { msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                })
            }
            composable(Destination.Settings.route) {
                SettingsScreen(onCheckForUpdates = updateViewModel::checkNow)
            }
        }

        updatePrompt?.let { available ->
            UpdateDialog(
                available = available,
                downloading = updateDownloading,
                onUpdate = updateViewModel::acceptUpdate,
                onLater = updateViewModel::dismiss,
                onSkip = updateViewModel::skip,
            )
        }
    }
}
