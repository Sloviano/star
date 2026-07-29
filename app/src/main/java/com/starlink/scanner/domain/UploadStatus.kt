package com.starlink.scanner.domain

/** Upload lifecycle of a locally-stored [com.starlink.scanner.data.local.ScanRecord]. */
enum class UploadStatus {
    /** Saved locally, not yet uploaded. */
    PENDING,

    /** Successfully uploaded to the Google Sheet. */
    SENT,

    /** Upload attempted and failed; will be retried by WorkManager. */
    FAILED,
}
