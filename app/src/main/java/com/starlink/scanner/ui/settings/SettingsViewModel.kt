package com.starlink.scanner.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.settings.SettingsRepository
import com.starlink.scanner.data.upload.SheetsUploader
import com.starlink.scanner.data.upload.UploadOutcome
import com.starlink.scanner.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Result of the Settings "Test connection" probe, rendered inline under the URL field. */
sealed interface TestResult {
    data object Testing : TestResult
    data class Ok(val message: String) : TestResult
    data class Error(val message: String) : TestResult
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val uploader: SheetsUploader,
    scanDao: ScanDao,
) : ViewModel() {

    val sheetsUrl: StateFlow<String> =
        settings.sheetsUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // Diagnostics.
    val pendingCount: StateFlow<Int> =
        scanDao.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val lastUploadTime: StateFlow<Long> =
        settings.lastUploadTime.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val lastUploadError: StateFlow<String> =
        settings.lastUploadError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // In-app updater (Module 6) diagnostics + toggle.
    val autoUpdateCheck: StateFlow<Boolean> =
        settings.autoUpdateCheck.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val lastUpdateCheck: StateFlow<Long> =
        settings.lastUpdateCheck.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val currentVersion: String =
        "${ServiceLocator.currentVersionName()} (${ServiceLocator.currentVersionCode()})"

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    fun setSheetsUrl(url: String) = viewModelScope.launch { settings.setSheetsUrl(url) }

    fun setAutoUpdateCheck(enabled: Boolean) =
        viewModelScope.launch { settings.setAutoUpdateCheck(enabled) }

    /** Validate the URL shape, then POST an empty batch and report the backend's answer. */
    fun testConnection() {
        _testResult.value = TestResult.Testing
        viewModelScope.launch {
            val url = settings.sheetsUrl.first().trim()
            if (!looksLikeAppsScriptUrl(url)) {
                _testResult.value =
                    TestResult.Error("URL should look like script.google.com/macros/s/…/exec")
                return@launch
            }
            // Dry-run: authenticates and exercises the real spreadsheet write path, persisting no row.
            _testResult.value = when (val outcome = uploader.postDryRun(url)) {
                is UploadOutcome.Success -> TestResult.Ok("Connected — sheet reachable")
                is UploadOutcome.Failed -> TestResult.Error(outcome.reason)
            }
        }
    }

    private fun looksLikeAppsScriptUrl(url: String): Boolean =
        url.startsWith("https://script.google.com/macros/s/") && url.endsWith("/exec")

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                SettingsViewModel(
                    settings = ServiceLocator.settingsRepository,
                    uploader = ServiceLocator.sheetsUploader,
                    scanDao = ServiceLocator.scanDao,
                ) as T
        }
    }
}
