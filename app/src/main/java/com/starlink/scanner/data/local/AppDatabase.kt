package com.starlink.scanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

// v3: stripped ScanRecord to dishId/kitNumber/dishSerial (+ infra), dropping router serial, HW/SW
// version, country, and operator. Versions 1 and 2 never shipped — every released build (1.0
// onwards) is on v3 — so those two are still allowed to migrate destructively; see [LEGACY_VERSIONS].
//
// Schemas are exported to app/schemas so a future version bump can be migrated (and the migration
// verified against the recorded schema) rather than guessed at.
@Database(entities = [ScanRecord::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        /**
         * Pre-release schema versions, safe to wipe: they only ever existed on development devices.
         * Never extend this list with a version that has shipped — records sit in this database
         * unsynced (that is the whole point of the PENDING state), and dropping the table loses
         * field work that was never uploaded. Add a [Migration] to [MIGRATIONS] instead.
         */
        val LEGACY_VERSIONS = intArrayOf(1, 2)

        /**
         * Migrations for shipped schema versions, applied in order. Empty while v3 is the only
         * released schema. When you change [ScanRecord]: bump the `version` above, commit the JSON
         * Room writes into app/schemas, and add the `Migration(3, 4)` here — with no matching entry
         * Room now throws at open time instead of silently recreating the table.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
