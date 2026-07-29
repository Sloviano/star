package com.starlink.scanner.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.data.upload.SyncResult
import com.starlink.scanner.di.ServiceLocator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(scanDao: ScanDao) : ViewModel() {

    val records: StateFlow<List<ScanRecord>> = scanDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isSyncing = MutableStateFlow(false)
    /** True while a manual "Sync now" is in flight — drives the button spinner. */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** One-shot user feedback for a completed sync (shown as a Snackbar). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * User-initiated flush. Runs the upload *directly* (not via the constrained WorkManager job) so a
     * manual tap always attempts a POST — even on the dish's no-internet WiFi, where the background
     * job's `NetworkType.CONNECTED` constraint would otherwise hold it forever. Reports the outcome.
     */
    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            val message = when (val result = ServiceLocator.uploadRunner.run()) {
                is SyncResult.NothingPending -> "Nothing to sync"
                is SyncResult.NoUrl -> "Set the Apps Script URL in Settings first"
                is SyncResult.Sent -> "Synced ${result.count} record(s) ✓"
                is SyncResult.Failed -> "Sync failed: ${result.reason}"
            }
            _isSyncing.value = false
            _messages.tryEmit(message)
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                HistoryViewModel(ServiceLocator.scanDao) as T
        }
    }
}
