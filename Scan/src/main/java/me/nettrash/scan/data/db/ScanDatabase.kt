package me.nettrash.scan.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ScanRecord::class], version = 2, exportSchema = false)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun scanRecordDao(): ScanRecordDao

    companion object {
        /**
         * v1 → v2: add the `is_favorite` column to back the History
         * favourite-pin feature shipped in 1.2. `INTEGER NOT NULL DEFAULT 0`
         * matches the Kotlin side's `Boolean = false` default and means
         * existing rows materialise as un-favourited instead of NULL.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scan_records ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
