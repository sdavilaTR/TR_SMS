package com.example.hassiwrapper.data.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.hassiwrapper.data.db.dao.*
import com.example.hassiwrapper.data.db.entities.*

@Database(
    entities = [
        ConfigEntity::class,
        ProjectEntity::class,
        ZoneEntity::class,
        ContractorEntity::class,
        PersonEntity::class,
        AccessPointEntity::class,
        CryptoKeyEntity::class,
        RevokedTokenEntity::class,
        AccessLogEntity::class,
        IncidentEntity::class,
        WorkSessionEntity::class,
        PendingPhotoEntity::class,
        HseObservationEntity::class,
        VehicleEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AtlasDatabase : RoomDatabase() {

    abstract fun configDao(): ConfigDao
    abstract fun projectDao(): ProjectDao
    abstract fun zoneDao(): ZoneDao
    abstract fun contractorDao(): ContractorDao
    abstract fun personDao(): PersonDao
    abstract fun accessPointDao(): AccessPointDao
    abstract fun cryptoKeyDao(): CryptoKeyDao
    abstract fun revokedTokenDao(): RevokedTokenDao
    abstract fun accessLogDao(): AccessLogDao
    abstract fun incidentDao(): IncidentDao
    abstract fun workSessionDao(): WorkSessionDao
    abstract fun pendingPhotoDao(): PendingPhotoDao
    abstract fun hseObservationDao(): HseObservationDao
    abstract fun vehicleDao(): VehicleDao

    /** Clears all data from every table (used when switching to DEV profile). */
    suspend fun clearAllData() {
        clearAllTables()
    }

    companion object {
        private const val TAG = "AtlasDatabase"

        @Volatile
        private var INSTANCE: AtlasDatabase? = null

        /**
         * No-op migration that preserves all data when the schema hasn't changed.
         * Room requires an explicit Migration object for each version bump;
         * without one, [fallbackToDestructiveMigration] would silently wipe
         * every table — including unsynced access logs, incidents and sessions.
         */
        private fun noOpMigration(from: Int, to: Int) = object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration $from → $to (no-op, data preserved)")
            }
        }

        fun getInstance(context: Context): AtlasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AtlasDatabase::class.java,
                    "atlas_trac_db"
                )
                    // Explicit no-op migrations preserve pending data across version bumps.
                    // Only fall back to destructive migration from very old schema versions
                    // that are incompatible with the current schema.
                    .addMigrations(
                        noOpMigration(1, 2),
                        noOpMigration(2, 3),
                        noOpMigration(3, 4),
                        noOpMigration(4, 5)
                    )
                    .fallbackToDestructiveMigrationFrom(1, 2, 3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
