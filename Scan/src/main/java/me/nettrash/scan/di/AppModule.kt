package me.nettrash.scan.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.nettrash.scan.data.db.ScanDatabase
import me.nettrash.scan.data.db.ScanRecordDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ScanDatabase {
        return Room.databaseBuilder(
            context,
            ScanDatabase::class.java,
            "scan_database"
        )
            // Add new ALTER TABLE migrations here as they're written
            // — never use fallbackToDestructiveMigration in production:
            // a Play upgrade silently nuking the user's saved scans
            // would be a strictly worse outcome than crashing visibly.
            .addMigrations(ScanDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideScanRecordDao(database: ScanDatabase): ScanRecordDao {
        return database.scanRecordDao()
    }
}
