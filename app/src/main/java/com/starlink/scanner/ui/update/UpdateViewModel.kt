package com.starlink.scanner.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.starlink.scanner.data.settings.SettingsRepository
import com.starlink.scanner.data.update.ApkInstaller
import com.starlink.scanner.data.update.UpdateChecker
import com.starlink.scanner.data.update.UpdateStatus
import com.starlink.scanner.di.ServiceLocator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Orchestrates the in-app updater (Module 6): the launch-time check, the manual "Check now", and the
 * download + install once the user accepts. Hoisted at [com.starlink.scanner.ui.navigation.AppNavigation]
 * so the prompt overlays every screen; the manual check is triggered from Settings via a callback.
 */
class UpdateViewModel(
    private val checker: UpdateChecker,
    private val installer: ApkInstaller,
    private val settings: SettingsRepository,
    private val currentVersionCode: Long,
) : ViewModel() {

    /** Non-null while the "update available" prompt should be shown. */
    private val _prompt = MutableStateFlow<UpdateStatus.Available?>(null)
    val prompt: StateFlow<UpdateStatus.Available?> = _prompt.asStateFlow()

    /** True while the accepted update is downloading (drives the dialog's progress state). */
    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    /** One-shot user messages (snackbar): errors, "up to date", etc. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Launch-time check: silent unless a newer, non-skipped build is found. */
    fun checkOnLaunch() {
        viewModelScope.launch {
            if (!settings.autoUpdateCheck.first()) return@launch
            when (val status = runCheck()) {
                is UpdateStatus.Available ->
                    if (status.versionCode != settings.skippedVersionCode.first()) _prompt.value = status
                else -> Unit // Stay quiet on launch for UpToDate / Failed.
            }
        }
    }

    /** Manual check (Settings ▸ Check for updates now): always reports the outcome. */
    fun checkNow() {
        viewModelScope.launch {
            when (val status = runCheck()) {
                is UpdateStatus.Available -> _prompt.value = status
                is UpdateStatus.UpToDate -> _messages.emit("You're on the latest version")
                is UpdateStatus.Failed -> _messages.emit("Update check failed: ${status.reason}")
            }
        }
    }

    private suspend fun runCheck(): UpdateStatus {
        val status = checker.check(currentVersionCode)
        settings.setLastUpdateCheck(System.currentTimeMillis())
        return status
    }

    /** "Update" button: ensure install permission, download the APK, launch the installer. */
    fun acceptUpdate() {
        val available = _prompt.value ?: return
        if (!installer.canInstall()) {
            installer.promptEnableUnknownSources()
            _messages.tryEmit("Allow installs from this app, then tap Update again")
            return
        }
        viewModelScope.launch {
            _downloading.value = true
            try {
                val apk = installer.download(available.apkDownloadUrl)
                _prompt.value = null
                installer.install(apk)
            } catch (e: Exception) {
                _messages.emit("Download failed: ${e.message ?: "unknown error"}")
            } finally {
                _downloading.value = false
            }
        }
    }

    /** "Skip this version": don't prompt again for this build. */
    fun skip() {
        val available = _prompt.value ?: return
        viewModelScope.launch { settings.setSkippedVersionCode(available.versionCode) }
        _prompt.value = null
    }

    /** "Later": dismiss without skipping (will prompt again next launch). */
    fun dismiss() {
        if (!_downloading.value) _prompt.value = null
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                UpdateViewModel(
                    checker = ServiceLocator.updateChecker,
                    installer = ServiceLocator.apkInstaller,
                    settings = ServiceLocator.settingsRepository,
                    currentVersionCode = ServiceLocator.currentVersionCode(),
                ) as T
        }
    }
}
