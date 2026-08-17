package com.starlink.scanner.data.upload

import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.domain.UploadStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire contract the Apps Script backend reads: `Code.gs` picks fields off each record by
 * name, so a rename here silently blanks a sheet column rather than failing the upload.
 */
class ScanUploadDtoTest {

    private val record = ScanRecord(
        id = 47,
        counter = 128,
        timestamp = 1_731_000_000_000,
        dishId = "01000000-00000000-00001234",
        kitNumber = "KIT-123456",
        dishSerial = "DISH-9",
        status = UploadStatus.PENDING,
    )

    @Test
    fun serializedBatch_carriesTheFieldNamesTheBackendReads() {
        val json = Json.encodeToString(
            ListSerializer(ScanUploadDto.serializer()),
            listOf(record.toUploadDto()),
        )

        assertEquals(
            """[{"counter":128,"timestamp":1731000000000,"dishId":"01000000-00000000-00001234",""" +
                """"kitNumber":"KIT-123456","dishSerial":"DISH-9"}]""",
            json,
        )
    }

    @Test
    fun counter_carriesTheRecordsSequenceNumber() {
        // Column A of the sheet. Records saved before the counter existed keep 0, which the backend
        // reads as "no number" and fills from the timestamp instead.
        assertEquals(128L, record.toUploadDto().counter)
        assertEquals(0L, record.copy(counter = 0).toUploadDto().counter)
    }
}
