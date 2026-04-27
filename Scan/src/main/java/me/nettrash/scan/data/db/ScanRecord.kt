package me.nettrash.scan.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One saved scan. Mirrors the iOS Core Data `ScanRecord` entity:
 * `id`, `value`, `symbology`, `timestamp`, `notes`.
 */
@Entity(tableName = "scan_records")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val value: String,
    val symbology: String,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null
)
