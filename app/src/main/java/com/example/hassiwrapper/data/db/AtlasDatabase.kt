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
        VehicleEntity::class,
        TrainingComplianceEntity::class,
        DocumentComplianceEntity::class
    ],
    version = 6,
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
    abstract fun trainingComplianceDao(): TrainingComplianceDao
    abstract fun documentComplianceDao(): DocumentComplianceDao

    /** Clears all data from every table (used when switching to DEV profile). */
    suspend fun clearAllData() {
        clearAllTables()
    }

    companion object {
        private const val TAG = "AtlasDatabase"

        @Volatile
        private var INSTANCE: AtlasDatabase? = null

        // v1 → v2: added pending_photos table
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 1 → 2: create pending_photos")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pending_photos` (
                        `unique_id_value` TEXT NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `local_path` TEXT NOT NULL,
                        `created_at` TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        // v2 → v3: added hse_observations table
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 2 → 3: create hse_observations")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `hse_observations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uuid` TEXT NOT NULL,
                        `project_id` INTEGER,
                        `observer_device_id` TEXT,
                        `observer_badge` TEXT,
                        `unique_id_value` TEXT,
                        `observed_name` TEXT,
                        `observed_badge` TEXT,
                        `observed_department` TEXT,
                        `observed_position` TEXT,
                        `observed_contractor` TEXT,
                        `observation_date` TEXT NOT NULL,
                        `location` TEXT,
                        `area_authority` TEXT,
                        `description` TEXT NOT NULL,
                        `observation_type` TEXT NOT NULL,
                        `safety_type` TEXT NOT NULL,
                        `intervention_action` TEXT,
                        `outcome` TEXT,
                        `action_taken` TEXT,
                        `coaching_status` TEXT NOT NULL DEFAULT 'NOT_REQUIRED',
                        `additional_comments` TEXT,
                        `categories` TEXT,
                        `synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // v3 → v4: removed entry_time and exit_time from work_sessions.
        // SQLite does not support DROP COLUMN before 3.35, so we recreate the table.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 3 → 4: drop entry_time/exit_time from work_sessions")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `work_sessions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uuid` TEXT,
                        `project_id` INTEGER,
                        `unique_id_value` TEXT NOT NULL,
                        `session_date` TEXT NOT NULL,
                        `first_entry_log_id` INTEGER,
                        `clock_in` TEXT,
                        `clock_out` TEXT,
                        `last_exit_log_id` INTEGER,
                        `status` TEXT NOT NULL DEFAULT 'OPEN',
                        `synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `work_sessions_new` (
                        `id`, `uuid`, `project_id`, `unique_id_value`, `session_date`,
                        `first_entry_log_id`, `clock_in`, `clock_out`,
                        `last_exit_log_id`, `status`, `synced`
                    ) SELECT
                        `id`, `uuid`, `project_id`, `unique_id_value`, `session_date`,
                        `first_entry_log_id`, `clock_in`, `clock_out`,
                        `last_exit_log_id`, `status`, `synced`
                    FROM `work_sessions`
                """.trimIndent())
                db.execSQL("DROP TABLE `work_sessions`")
                db.execSQL("ALTER TABLE `work_sessions_new` RENAME TO `work_sessions`")
            }
        }

        // v4 → v5: added vehicles table
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 4 → 5: create vehicles")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `vehicles` (
                        `asset_id` INTEGER NOT NULL PRIMARY KEY,
                        `asset_uuid` TEXT NOT NULL DEFAULT '',
                        `project_id` INTEGER,
                        `identifier` TEXT NOT NULL DEFAULT '',
                        `asset_name` TEXT NOT NULL DEFAULT '',
                        `vehicle_type_name` TEXT NOT NULL DEFAULT '',
                        `contractor_id` INTEGER,
                        `contractor_name` TEXT NOT NULL DEFAULT '',
                        `license_plate` TEXT NOT NULL DEFAULT '',
                        `owner_register_sn` TEXT NOT NULL DEFAULT '',
                        `brand` TEXT NOT NULL DEFAULT '',
                        `model` TEXT NOT NULL DEFAULT '',
                        `insurance_expiry` TEXT,
                        `inspection_expiry` TEXT,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `badge_printed` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // v5 → v6: added training_compliance and document_compliance tables
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 5 → 6: create training_compliance, document_compliance")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `training_compliance` (
                        `unique_id_value` TEXT NOT NULL,
                        `training_definition_id` INTEGER NOT NULL,
                        `badge_number` TEXT NOT NULL DEFAULT '',
                        `training_code` TEXT NOT NULL DEFAULT '',
                        `training_name` TEXT NOT NULL DEFAULT '',
                        `is_mandatory` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL DEFAULT 'MISSING',
                        `completed_date` TEXT,
                        `expiry_date` TEXT,
                        PRIMARY KEY(`unique_id_value`, `training_definition_id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `document_compliance` (
                        `unique_id_value` TEXT NOT NULL,
                        `document_type_id` INTEGER NOT NULL,
                        `type_code` TEXT NOT NULL DEFAULT '',
                        `type_name` TEXT NOT NULL DEFAULT '',
                        `is_mandatory` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL DEFAULT 'missing',
                        `person_document_id` INTEGER,
                        PRIMARY KEY(`unique_id_value`, `document_type_id`)
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AtlasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AtlasDatabase::class.java,
                    "atlas_trac_db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
