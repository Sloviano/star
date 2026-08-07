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

/**
 * Envelope wrapping a batch with the shared secret. The Web App is deployed "Anyone"-access and
 * cannot read custom HTTP headers, so the secret travels in the body.
 */
@Serializable
data class ScanBatchDto(
    val token: String,
    val records: List<ScanUploadDto>,
)

/** Settings ▸ Test connection probe: authenticates and resolves the sheet, but writes no row. */
@Serializable
data class DryRunDto(
    val token: String,
    val dryRun: Boolean = true,
)

fun ScanRecord.toUploadDto(): ScanUploadDto = ScanUploadDto(
    timestamp = timestamp,
    dishId = dishId,
    kitNumber = kitNumber,
    dishSerial = dishSerial,
)
