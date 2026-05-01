package me.nettrash.scan.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One saved scan. Mirrors the iOS Core Data `ScanRecord` entity:
 * `id`, `value`, `symbology`, `timestamp`, `notes`, plus the
 * `isFavorite` star flag added in 1.2.
 */
@Entity(tableName = "scan_records")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val value: String,
    val symbology: String,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null,
    /// New in 1.2 — kept nullable on the Kotlin side so a row inserted
    /// before the schema bump (which materialises as NULL after the
    /// migration) doesn't trip Room's strict-not-null contract. `false`
    /// is the default for new inserts.
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
)
