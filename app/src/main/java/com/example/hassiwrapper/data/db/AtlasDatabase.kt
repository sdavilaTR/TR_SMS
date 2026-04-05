package com.example.hassiwrapper.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        @Volatile
        private var INSTANCE: AtlasDatabase? = null

        fun getInstance(context: Context): AtlasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AtlasDatabase::class.java,
                    "atlas_trac_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
