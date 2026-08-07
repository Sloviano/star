package com.starlink.scanner.data.upload

import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.domain.UploadStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contract the Apps Script backend reads. The idempotency guarantee is a two-sided
 * agreement — `Code.gs` deduplicates on the `uploadKey` field by name, and a record must produce the
 * same key on every retry — so both are asserted here rather than left to the backend to discover.
 */
class ScanUploadDtoTest {

    private val record = ScanRecord(
        id = 47,
        timestamp = 1_731_000_000_000,
        dishId = "01000000-00000000-00001234",
        kitNumber = "KIT-123456",
        dishSerial = "DISH-9",
        status = UploadStatus.PENDING,
    )

    @Test
    fun uploadKey_combinesInstallIdAndRowId() {
        assertEquals("install-a:47", record.toUploadDto("install-a").uploadKey)
    }

    @Test
    fun uploadKey_isStableAcrossRetriesOfTheSameRecord() {
        // The retry path re-reads the record from Room, so equal rows must map to an equal key —
        // otherwise a re-sent batch looks new to the backend and duplicates every row.
        assertEquals(
            record.toUploadDto("install-a").uploadKey,
            record.copy().toUploadDto("install-a").uploadKey,
        )
    }

    @Test
    fun uploadKey_differsPerRecordAndPerInstall() {
        assertTrue(
            record.toUploadDto("install-a").uploadKey != record.copy(id = 48).toUploadDto("install-a").uploadKey
        )
        // Installs share one sheet, so row ids alone would collide across devices.
        assertTrue(
            record.toUploadDto("install-a").uploadKey != record.toUploadDto("install-b").uploadKey
        )
    }

    @Test
    fun serializedBatch_carriesTheFieldNamesTheBackendReads() {
        val json = Json.encodeToString(
            ListSerializer(ScanUploadDto.serializer()),
            listOf(record.toUploadDto("install-a")),
        )

        assertEquals(
            """[{"timestamp":1731000000000,"dishId":"01000000-00000000-00001234",""" +
                """"kitNumber":"KIT-123456","dishSerial":"DISH-9","uploadKey":"install-a:47"}]""",
            json,
        )
    }
}
