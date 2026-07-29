package com.starlink.scanner.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.starlink.scanner.domain.DishInfo
import com.starlink.scanner.domain.UploadStatus

/** One provisioning record: dish ID (gRPC) + scanned kit number and dish serial, plus upload state. */
@Entity(tableName = "scan_records")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dishId: String,
    val kitNumber: String,
    val dishSerial: String,
    val status: UploadStatus = UploadStatus.PENDING,
    val attempts: Int = 0,
) {
    fun toDishInfo(): DishInfo = DishInfo(dishId)
}
