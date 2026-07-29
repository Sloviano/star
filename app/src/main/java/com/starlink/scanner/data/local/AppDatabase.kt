package com.starlink.scanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// v3: stripped ScanRecord to dishId/kitNumber/dishSerial (+ infra), dropping router serial, HW/SW
// version, country, and operator. Destructive migration recreates the table (see ServiceLocator).
@Database(entities = [ScanRecord::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
}
