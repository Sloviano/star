package com.starlink.scanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Insert
    suspend fun insert(record: ScanRecord): Long

    /** All records, newest first — drives the History list. */
    @Query("SELECT * FROM scan_records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ScanRecord>>

    @Query("SELECT * FROM scan_records WHERE id = :id")
    suspend fun getById(id: Long): ScanRecord?

    /** Records still needing upload — consumed in one batch by the UploadWorker (Module 5). */
    @Query("SELECT * FROM scan_records WHERE status IN ('PENDING','FAILED') ORDER BY timestamp ASC")
    suspend fun pending(): List<ScanRecord>

    /** Live count of not-yet-sent records — drives the status strip badge. */
    @Query("SELECT COUNT(*) FROM scan_records WHERE status IN ('PENDING','FAILED')")
    fun pendingCount(): Flow<Int>

    /** How many records already exist for this dish+kit pair (duplicate detection). */
    @Query("SELECT COUNT(*) FROM scan_records WHERE dishId = :dishId AND kitNumber = :kitNumber")
    suspend fun countMatching(dishId: String, kitNumber: String): Int

    /**
     * Settle a whole uploaded batch in one statement. Per-row updates could be interrupted partway
     * (process death, worker cancellation), leaving accepted records still PENDING and re-sent on
     * the next run; a single UPDATE either applies to the batch or to none of it.
     */
    @Query("UPDATE scan_records SET status = 'SENT' WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>)

    @Query("UPDATE scan_records SET status = 'FAILED', attempts = attempts + 1 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<Long>)
}
