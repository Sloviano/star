package com.starlink.scanner.data.local

import androidx.room.TypeConverter
import com.starlink.scanner.domain.UploadStatus

class Converters {
    @TypeConverter
    fun fromStatus(status: UploadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): UploadStatus = UploadStatus.valueOf(value)
}
