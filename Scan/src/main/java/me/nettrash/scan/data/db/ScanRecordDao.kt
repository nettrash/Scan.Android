package me.nettrash.scan.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRecordDao {

    @Insert
    suspend fun insert(record: ScanRecord): Long

    @Update
    suspend fun update(record: ScanRecord)

    @Delete
    suspend fun delete(record: ScanRecord)

    /// Favourites first, then newest-first within each bucket. The
    /// HistoryScreen used to do the favourite sort in Kotlin; pushing
    /// it into SQL keeps the LazyColumn key stable across reorders.
    @Query("SELECT * FROM scan_records ORDER BY is_favorite DESC, timestamp DESC")
    fun observeAll(): Flow<List<ScanRecord>>

    @Query("SELECT * FROM scan_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScanRecord?

    /// Targeted favourite toggle — cheaper than the full `update()`
    /// route because Room doesn't have to diff every column.
    @Query("UPDATE scan_records SET is_favorite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("DELETE FROM scan_records")
    suspend fun deleteAll()
}
