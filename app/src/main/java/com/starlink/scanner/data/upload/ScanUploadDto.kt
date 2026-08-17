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
    /** Sequence number written to the sheet's first column; 0 on records saved before it existed. */
    val counter: Long,
    /**
     * Kept on the wire although the sheet's first column now holds [counter]: an installed build
     * uploading into a not-yet-redeployed Apps Script still fills that column with a timestamp
     * rather than leaving it blank.
     */
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
    counter = counter,
    timestamp = timestamp,
    dishId = dishId,
    kitNumber = kitNumber,
    dishSerial = dishSerial,
)
