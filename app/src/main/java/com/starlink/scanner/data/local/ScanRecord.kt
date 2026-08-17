package com.starlink.scanner.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.starlink.scanner.domain.DishInfo
import com.starlink.scanner.domain.UploadStatus

/** One provisioning record: dish ID (gRPC) + scanned kit number and dish serial, plus upload state. */
@Entity(tableName = "scan_records")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * Sequence number for the sheet's first column, taken from the technician-settable counter in
     * Settings when the record is saved (see
     * [com.starlink.scanner.data.settings.SettingsRepository.takeNextCounter]). Distinct from [id]:
     * the counter is theirs to set to any starting number, so it can be aligned with a paper log or
     * a second phone's range, while row ids are per-install and never reused.
     *
     * 0 on records saved by a build that predates the counter — the backend falls back to writing
     * their upload timestamp, which is what column A held before.
     */
    val counter: Long = 0,
    val timestamp: Long,
    val dishId: String,
    val kitNumber: String,
    val dishSerial: String,
    val status: UploadStatus = UploadStatus.PENDING,
    val attempts: Int = 0,
) {
    fun toDishInfo(): DishInfo = DishInfo(dishId)
}
