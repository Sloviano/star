package com.starlink.scanner.data.upload

import com.starlink.scanner.data.local.ScanRecord
import kotlinx.serialization.Serializable

/**
 * Wire format for one record posted to the Apps Script Web App. Kept separate from the Room
 * [ScanRecord] entity so persistence and transport can evolve independently. Field names match the
 * keys the `apps-script/Code.gs` `doPost` handler reads.
 */
@Serializable
data class ScanUploadDto(
    val timestamp: Long,
    val dishId: String,
    val kitNumber: String,
    val dishSerial: String,
)

fun ScanRecord.toUploadDto(): ScanUploadDto = ScanUploadDto(
    timestamp = timestamp,
    dishId = dishId,
    kitNumber = kitNumber,
    dishSerial = dishSerial,
)
