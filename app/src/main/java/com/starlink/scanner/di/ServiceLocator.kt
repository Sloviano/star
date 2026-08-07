package com.starlink.scanner.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import com.starlink.scanner.data.local.AppDatabase
import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.network.DishNetworkMonitor
import com.starlink.scanner.data.network.DishNetworkSource
import com.starlink.scanner.data.network.DishReachability
import com.starlink.scanner.data.network.StarlinkWifiConnector
import com.starlink.scanner.data.network.TcpDishReachability
import com.starlink.scanner.data.settings.SettingsRepository
import com.starlink.scanner.data.starlink.RealStarlinkRepository
import com.starlink.scanner.data.starlink.StarlinkRepository
import com.starlink.scanner.data.update.ApkInstaller
import com.starlink.scanner.data.update.UpdateChecker
import com.starlink.scanner.data.upload.SheetsUploader
import com.starlink.scanner.data.upload.UploadRunner
import com.starlink.scanner.data.upload.UploadScheduler

/**
 * Minimal manual dependency container (Hilt is optional per the spec). Initialized once from
 * [com.starlink.scanner.ScannerApplication] and read by ViewModel factories.
 */
object ServiceLocator {

    private lateinit var appContext: Context
    private lateinit var database: AppDatabase

    lateinit var scanDao: ScanDao
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    // Dish WiFi detection & socket-binding source (Module 2).
    lateinit var dishNetworkSource: DishNetworkSource
        private set

    // App-initiated one-tap connect to the dish's open "STARLINK…" AP (API 29+).
    lateinit var starlinkWifiConnector: StarlinkWifiConnector
        private set

    // Dish-LAN reachability probe (Module 2): bound TCP connect to 192.168.100.1:9200.
    val dishReachability: DishReachability = TcpDishReachability()

    // Real gRPC client to the dish's local API (Module 1).
    val starlinkRepository: StarlinkRepository = RealStarlinkRepository()

    // HTTP uploader to the Apps Script Web App (Module 5).
    val sheetsUploader: SheetsUploader = SheetsUploader()

    // Shared one-shot upload of pending records (Module 5) — used by the worker and manual "Sync now".
    lateinit var uploadRunner: UploadRunner
        private set

    // In-app updater (Module 6): checks GitHub Releases; installs a newer APK.
    val updateChecker: UpdateChecker = UpdateChecker()

    lateinit var apkInstaller: ApkInstaller
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        database = Room.databaseBuilder(appContext, AppDatabase::class.java, "starlink-scanner.db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            // Only the pre-release schemas may be recreated. Every other missing migration is a bug
            // that must fail loudly here rather than quietly drop records the technician captured
            // offline and hasn't uploaded yet.
            .fallbackToDestructiveMigrationFrom(true, *AppDatabase.LEGACY_VERSIONS)
            .build()
        scanDao = database.scanDao()
        settingsRepository = SettingsRepository(appContext)
        uploadRunner = UploadRunner(scanDao, settingsRepository, sheetsUploader)

        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        dishNetworkSource = DishNetworkMonitor(cm)
        starlinkWifiConnector = StarlinkWifiConnector(cm)

        apkInstaller = ApkInstaller(appContext)
    }

    /** Installed build's versionCode — compared against the latest GitHub release (Module 6). */
    fun currentVersionCode(): Long =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode

    /** Installed build's versionName — shown in Settings diagnostics. */
    fun currentVersionName(): String =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()

    /** Enqueue the deferred upload job. [force] restarts it now (History's "Sync now"). */
    fun enqueueUpload(force: Boolean = false) = UploadScheduler.enqueue(appContext, force)

    /** True while the upload job is enqueued/running — drives the "Sync now" spinner. */
    fun uploadRunning() = UploadScheduler.isRunning(appContext)
}
