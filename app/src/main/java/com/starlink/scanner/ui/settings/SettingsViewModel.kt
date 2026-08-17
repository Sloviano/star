package com.starlink.scanner.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.network.StarlinkWifiSuggester
import com.starlink.scanner.data.network.SuggestResult
import com.starlink.scanner.data.settings.SettingsRepository
import com.starlink.scanner.data.upload.SheetsUploader
import com.starlink.scanner.data.upload.UploadOutcome
import com.starlink.scanner.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val wifiSuggester: StarlinkWifiSuggester,
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

    // Device-wide dish WiFi (network suggestion).
    val autoJoinDishWifi: StateFlow<Boolean> =
        settings.autoJoinDishWifi.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The learned dish SSID, or the stock default when nothing has been learned yet. */
    val dishSsid: StateFlow<String> = settings.dishSsid
        .map { it.ifBlank { StarlinkWifiSuggester.DEFAULT_SSIDS.first() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** True where network suggestions exist at all (API 29+) — the toggle is hidden otherwise. */
    val canSuggestWifi: Boolean = wifiSuggester.isSupported

    /** Outcome of the last auto-join toggle, rendered under the switch. */
    private val _autoJoinResult = MutableStateFlow<TestResult?>(null)
    val autoJoinResult: StateFlow<TestResult?> = _autoJoinResult.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    /** The number the next saved record will carry into the sheet's first column. */
    val nextCounter: StateFlow<Long> = settings.nextCounter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_COUNTER)

    /** Outcome of the last counter edit, rendered under the field. */
    private val _counterResult = MutableStateFlow<TestResult?>(null)
    val counterResult: StateFlow<TestResult?> = _counterResult.asStateFlow()

    fun setSheetsUrl(url: String) = viewModelScope.launch { settings.setSheetsUrl(url) }

    /**
     * Set the number the next saved record writes into the sheet's first column. Non-numeric or
     * negative input is rejected rather than coerced — silently starting from 0 would misnumber
     * every following row.
     */
    fun setNextCounter(raw: String) {
        val value = raw.trim().toLongOrNull()
        if (value == null || value < 0) {
            _counterResult.value = TestResult.Error("Enter a whole number")
            return
        }
        viewModelScope.launch {
            settings.setNextCounter(value)
            _counterResult.value = TestResult.Ok("Next record will be #$value")
        }
    }

    /**
     * Register or withdraw the dish AP suggestion, then persist the toggle.
     *
     * The suggestion covers the learned SSID *and* the stock default, because a suggestion can only
     * name an SSID exactly and the technician may not have connected to this dish before. Success
     * here means Android accepted the suggestion — the phone joins the AP when it next sees it, and
     * the first time it does, Android asks the user to approve suggestions from this app.
     */
    fun setAutoJoinDishWifi(enabled: Boolean) {
        viewModelScope.launch {
            val result = if (enabled) {
                val learned = settings.dishSsid.first()
                wifiSuggester.register(StarlinkWifiSuggester.DEFAULT_SSIDS + learned)
            } else {
                wifiSuggester.withdraw()
            }
            _autoJoinResult.value = when (result) {
                is SuggestResult.Registered -> {
                    settings.setAutoJoinDishWifi(true)
                    TestResult.Ok("Android will join the dish WiFi when it's in range")
                }
                is SuggestResult.Withdrawn -> {
                    settings.setAutoJoinDishWifi(false)
                    null
                }
                is SuggestResult.Unsupported ->
                    TestResult.Error("This device doesn't support WiFi suggestions (needs Android 10+)")
                is SuggestResult.Failed -> TestResult.Error(result.reason)
            }
        }
    }

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
                    wifiSuggester = ServiceLocator.starlinkWifiSuggester,
                    scanDao = ServiceLocator.scanDao,
                ) as T
        }
    }
}
