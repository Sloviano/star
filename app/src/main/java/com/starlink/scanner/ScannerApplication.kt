package com.starlink.scanner

import android.app.Application
import com.starlink.scanner.di.ServiceLocator

class ScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Flush anything left PENDING/FAILED from a previous session once there's connectivity.
        ServiceLocator.enqueueUpload()
    }
}
