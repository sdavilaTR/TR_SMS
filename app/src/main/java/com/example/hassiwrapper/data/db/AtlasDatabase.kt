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
        HseObservationPhotoEntity::class,
        VehicleEntity::class,
        TrainingComplianceEntity::class,
        DocumentComplianceEntity::class,
        SmsAreaEntity::class,
        SmsSubPositionEntity::class,
        SmsBoreSizeEntity::class,
        SmsIncompleteStatusEntity::class,
        SmsIsoTypeEntity::class,
        SmsPackingListEntity::class,
        SmsPackingListSpoolEntity::class,
        SmsPositionEntity::class,
        SmsSpecEntity::class,
        SmsSpoolEntity::class,
        SmsSpoolEventEntity::class,
        SmsSpoolPropertyEntity::class,
        SmsSpoolStatusEntity::class,
        SmsSpoolStatusFlagsEntity::class,
        SmsSubcontractorEntity::class,
        SmsUnitEntity::class,
        SmsVehicleEntity::class,
        SmsVehicleLoadingEntity::class,
        SmsVehicleLoadingSpoolEntity::class,
        SmsTransferEntity::class,
        SmsTransferSpoolEntity::class,
        SmsIncidentEntity::class,
        SmsOutboxEntity::class,
        SmsIdMapEntity::class,
        SmsAuditLogEntity::class,
        SmsSpoolLocationEntity::class
    ],
    version = 37,
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
    abstract fun hseObservationPhotoDao(): HseObservationPhotoDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun trainingComplianceDao(): TrainingComplianceDao
    abstract fun documentComplianceDao(): DocumentComplianceDao
    abstract fun smsAreaDao(): SmsAreaDao
    abstract fun smsBoreSizeDao(): SmsBoreSizeDao
    abstract fun smsIncompleteStatusDao(): SmsIncompleteStatusDao
    abstract fun smsIsoTypeDao(): SmsIsoTypeDao
    abstract fun smsPackingListDao(): SmsPackingListDao
    abstract fun smsPackingListSpoolDao(): SmsPackingListSpoolDao
    abstract fun smsPositionDao(): SmsPositionDao
    abstract fun smsSubPositionDao(): SmsSubPositionDao
    abstract fun smsSpecDao(): SmsSpecDao
    abstract fun smsSpoolDao(): SmsSpoolDao
    abstract fun smsSpoolEventDao(): SmsSpoolEventDao
    abstract fun smsSpoolPropertyDao(): SmsSpoolPropertyDao
    abstract fun smsSpoolStatusDao(): SmsSpoolStatusDao
    abstract fun smsSpoolStatusFlagsDao(): SmsSpoolStatusFlagsDao
    abstract fun smsSubcontractorDao(): SmsSubcontractorDao
    abstract fun smsUnitDao(): SmsUnitDao
    abstract fun smsVehicleDao(): SmsVehicleDao
    abstract fun smsVehicleLoadingDao(): SmsVehicleLoadingDao
    abstract fun smsTransferDao(): SmsTransferDao
    abstract fun smsIncidentDao(): SmsIncidentDao
    abstract fun smsOutboxDao(): SmsOutboxDao
    abstract fun smsAuditLogDao(): SmsAuditLogDao
    abstract fun smsSpoolLocationDao(): SmsSpoolLocationDao

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

        // v7 → v8: added all 16 SMS tables
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 7 → 8: create SMS tables")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_area` (
                        `area_id` INTEGER NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `parent_area_id` INTEGER,
                        `name` TEXT NOT NULL DEFAULT '',
                        `full_path` TEXT NOT NULL DEFAULT '',
                        `level` INTEGER NOT NULL DEFAULT 0,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT NOT NULL DEFAULT '',
                        `updated_at` TEXT,
                        `updated_by` TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_bore_size` (
                        `bore_size_id` INTEGER NOT NULL PRIMARY KEY,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `sort_order` INTEGER,
                        `is_active` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_incomplete_status` (
                        `incomplete_status_id` INTEGER NOT NULL PRIMARY KEY,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `sort_order` INTEGER,
                        `is_active` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_iso_type` (
                        `iso_type_id` INTEGER NOT NULL PRIMARY KEY,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `sort_order` INTEGER,
                        `is_active` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_packing_list` (
                        `packing_list_id` INTEGER NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `packing_list_name` TEXT NOT NULL DEFAULT '',
                        `vehicle_id` INTEGER,
                        `position_id` INTEGER,
                        `packing_date` TEXT NOT NULL DEFAULT '',
                        `total_spools_count` INTEGER,
                        `total_weight_kg` REAL,
                        `notes` TEXT,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT,
                        `updated_at` TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_packing_list_spool` (
                        `packing_list_spool_id` INTEGER NOT NULL PRIMARY KEY,
                        `packing_list_id` INTEGER NOT NULL,
                        `spool_id` INTEGER NOT NULL,
                        `sequence_number` INTEGER,
                        `added_at` TEXT NOT NULL DEFAULT '',
                        `added_by` TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_position` (
                        `position_id` INTEGER NOT NULL PRIMARY KEY,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `sort_order` INTEGER,
                        `is_active` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_spec` (
                        `spec_id` INTEGER NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `code` TEXT NOT NULL DEFAULT '',
                        `description` TEXT,
                        `material_type` TEXT,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT NOT NULL DEFAULT '',
                        `updated_at` TEXT,
                        `updated_by` TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_spool` (
                        `spool_id` INTEGER NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `spool_code` TEXT NOT NULL DEFAULT '',
                        `spool_suffix` TEXT,
                        `line_code` TEXT,
                        `unit_id` INTEGER,
                        `service` TEXT,
                        `train` TEXT,
                        `module` TEXT,
                        `iso_type_id` INTEGER,
                        `spec_id` INTEGER,
                        `iso_revision_date` TEXT,
                        `subcontractor_id` INTEGER,
                        `area_id` INTEGER,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT NOT NULL DEFAULT '',
                        `updated_at` TEXT,
                        `updated_by` TEXT,
                        `packing_list_id` INTEGER
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_spool_event` (
                        `event_id` INTEGER NOT NULL PRIMARY KEY,
                        `event_date` TEXT NOT NULL DEFAULT '',
                        `spool_id` INTEGER NOT NULL,
                        `event_type` TEXT NOT NULL DEFAULT '',
                        `old_value` TEXT,
                        `new_value` TEXT,
                        `source` TEXT,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_spool_property` (
                        `spool_id` INTEGER NOT NULL PRIMARY KEY,
                        `diameter_inches` REAL,
                        `diameter` REAL,
                        `bore_size_id` INTEGER,
                        `weight_kg` REAL,
                        `updated_at` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_spool_status` (
                        `status_id` INTEGER NOT NULL PRIMARY KEY,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `sort_order` INTEGER,
                        `is_active` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_spool_status_flags` (
                        `spool_id` INTEGER NOT NULL PRIMARY KEY,
                        `status_id` INTEGER,
                        `incomplete_status_id` INTEGER,
                        `position_id` INTEGER,
                        `hold` INTEGER NOT NULL DEFAULT 0,
                        `damaged` INTEGER NOT NULL DEFAULT 0,
                        `returned_to_factory` INTEGER NOT NULL DEFAULT 0,
                        `position_status_discrepancy` INTEGER NOT NULL DEFAULT 0,
                        `review_discrepancy` INTEGER NOT NULL DEFAULT 0,
                        `last_event_date` TEXT,
                        `pca_status_date` TEXT,
                        `pca_entry_date` TEXT,
                        `updated_at` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_subcontractor` (
                        `subcontractor_id` INTEGER NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT NOT NULL DEFAULT '',
                        `updated_at` TEXT,
                        `updated_by` TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_unit` (
                        `unit_id` INTEGER NOT NULL PRIMARY KEY,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `sort_order` INTEGER,
                        `is_active` INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_vehicle` (
                        `vehicle_id` INTEGER NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `company` TEXT,
                        `license_plate` TEXT NOT NULL DEFAULT '',
                        `vehicle_name` TEXT,
                        `vehicle_type` TEXT,
                        `capacity_weight_kg` REAL,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT,
                        `updated_at` TEXT
                    )
                """.trimIndent())
            }
        }

        // v6 → v7: added observation multi-target fields + photos table
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 6 → 7: extend hse_observations + create hse_observation_photos")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `target_type` TEXT NOT NULL DEFAULT 'WORKER'")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `observer_unique_id` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `observer_name` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `observer_position` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `observer_contractor` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `vehicle_asset_id` INTEGER")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `vehicle_identifier` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `vehicle_name` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `vehicle_type` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `vehicle_contractor` TEXT")
                db.execSQL("ALTER TABLE `hse_observations` ADD COLUMN `equipment_description` TEXT")
                db.execSQL("UPDATE `hse_observations` SET `target_type` = 'WORKER' WHERE `target_type` IS NULL OR `target_type` = ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `hse_observation_photos` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uuid` TEXT NOT NULL,
                        `observation_uuid` TEXT NOT NULL,
                        `local_path` TEXT NOT NULL,
                        `file_name` TEXT,
                        `sort_order` INTEGER NOT NULL DEFAULT 0,
                        `synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // v8 → v9: added vehicle_plate column to sms_packing_list
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 8 → 9: add vehicle_plate to sms_packing_list")
                db.execSQL("ALTER TABLE `sms_packing_list` ADD COLUMN `vehicle_plate` TEXT")
            }
        }

        // v9 → v10: added synced column to sms_spool
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 9 → 10: add synced to sms_spool")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `synced` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 11 → 12: add synced to sms_packing_list")
                db.execSQL("ALTER TABLE `sms_packing_list` ADD COLUMN `synced` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 12 → 13: add synced to sms_vehicle")
                db.execSQL("ALTER TABLE `sms_vehicle` ADD COLUMN `synced` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 13 → 14: create sms_vehicle_loading tables")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_vehicle_loading` (
                        `loading_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `vehicle_id` INTEGER NOT NULL,
                        `vehicle_plate` TEXT NOT NULL DEFAULT '',
                        `project_id` INTEGER NOT NULL,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_vehicle_loading_spool` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `loading_id` INTEGER NOT NULL,
                        `spool_id` INTEGER NOT NULL,
                        `spool_code` TEXT NOT NULL DEFAULT '',
                        `spool_suffix` TEXT,
                        `packing_list_id` INTEGER,
                        `packing_list_name` TEXT
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 15 → 16: add on_route + destination to sms_vehicle")
                db.execSQL("ALTER TABLE `sms_vehicle` ADD COLUMN `on_route` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sms_vehicle` ADD COLUMN `destination` INTEGER")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 16 → 17: add ready_to_send to sms_packing_list")
                db.execSQL("ALTER TABLE `sms_packing_list` ADD COLUMN `ready_to_send` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 17 → 18: add route_synced to sms_vehicle")
                db.execSQL("ALTER TABLE `sms_vehicle` ADD COLUMN `route_synced` INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 18 → 19: create sms_incident table")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_incident` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `uuid` TEXT NOT NULL,
                        `project_id` INTEGER NOT NULL,
                        `spool_code` TEXT NOT NULL,
                        `spool_suffix` TEXT,
                        `description` TEXT NOT NULL,
                        `vehicle_plate` TEXT,
                        `location_type` TEXT NOT NULL,
                        `location_detail` TEXT,
                        `severity` TEXT NOT NULL,
                        `position_id` INTEGER,
                        `position_code` TEXT,
                        `author_name` TEXT,
                        `photo_path` TEXT,
                        `event_date` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'OPEN',
                        `synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 19 → 20: add sms_incident close fields")
                db.execSQL("ALTER TABLE `sms_incident` ADD COLUMN `closed_by` TEXT")
                db.execSQL("ALTER TABLE `sms_incident` ADD COLUMN `closed_at` TEXT")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 20 → 21: add position_id to sms_spool")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `position_id` INTEGER")
            }
        }

        // v21 → v22: persisted mutation outbox + local→server id map
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 23 → 24: add position column to sms_packing_list")
                db.execSQL("ALTER TABLE sms_packing_list ADD COLUMN position TEXT")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 22 → 23: create sms_audit_log")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_audit_log` (
                        `log_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `project_id` INTEGER NOT NULL,
                        `action_type` TEXT NOT NULL,
                        `entity_type` TEXT NOT NULL,
                        `entity_id` INTEGER NOT NULL,
                        `entity_name` TEXT NOT NULL,
                        `detail` TEXT,
                        `terminal_name` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 21 → 22: create sms_outbox + sms_id_map")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_outbox` (
                        `op_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entity_type` TEXT NOT NULL,
                        `op_type` TEXT NOT NULL,
                        `local_entity_id` INTEGER NOT NULL,
                        `ref_entity_id` INTEGER,
                        `payload_json` TEXT,
                        `project_id` INTEGER NOT NULL,
                        `created_at` TEXT NOT NULL,
                        `attempts` INTEGER NOT NULL DEFAULT 0,
                        `last_error` TEXT,
                        `status` TEXT NOT NULL DEFAULT 'PENDING'
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_id_map` (
                        `entity_type` TEXT NOT NULL,
                        `local_id` INTEGER NOT NULL,
                        `server_id` INTEGER NOT NULL,
                        PRIMARY KEY(`entity_type`, `local_id`)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 14 → 15: create sms_transfer tables")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_transfer` (
                        `transfer_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transfer_type` TEXT NOT NULL DEFAULT '',
                        `packing_list_id` INTEGER NOT NULL,
                        `packing_list_name` TEXT NOT NULL DEFAULT '',
                        `vehicle_id` INTEGER NOT NULL,
                        `vehicle_plate` TEXT NOT NULL DEFAULT '',
                        `origin_location` TEXT NOT NULL DEFAULT '',
                        `destination_location` TEXT NOT NULL DEFAULT '',
                        `signature_data` TEXT NOT NULL DEFAULT '',
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `project_id` INTEGER NOT NULL,
                        `synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_transfer_spool` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transfer_id` INTEGER NOT NULL,
                        `spool_id` INTEGER NOT NULL,
                        `spool_code` TEXT NOT NULL DEFAULT '',
                        `spool_suffix` TEXT,
                        `assignment` TEXT
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 10 → 11: add API fields to sms_spool")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `status` TEXT")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `description` TEXT")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `priority` TEXT")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `zone` TEXT")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `assigned_unit` TEXT")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `packing_list_name` TEXT")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `in_transit` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v24 → v25: create sms_sub_position (Laydown/Site sub-sections) + link from sms_incident
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 24 → 25: create sms_sub_position, add sub_position_id to sms_incident")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_sub_position` (
                        `sub_position_id` INTEGER NOT NULL PRIMARY KEY,
                        `project_id` INTEGER NOT NULL,
                        `position_id` INTEGER NOT NULL,
                        `parent_sub_id` INTEGER,
                        `code` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `full_path` TEXT NOT NULL DEFAULT '',
                        `level` INTEGER NOT NULL DEFAULT 0,
                        `is_active` INTEGER NOT NULL DEFAULT 1,
                        `created_at` TEXT NOT NULL DEFAULT '',
                        `created_by` TEXT NOT NULL DEFAULT '',
                        `updated_at` TEXT,
                        `updated_by` TEXT
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE `sms_incident` ADD COLUMN `sub_position_id` INTEGER")
            }
        }

        // v25 → v26: track server-assigned incident id + photo upload state separately from `synced`
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 25 → 26: add server_id, photo_synced to sms_incident")
                db.execSQL("ALTER TABLE `sms_incident` ADD COLUMN `server_id` INTEGER")
                db.execSQL("ALTER TABLE `sms_incident` ADD COLUMN `photo_synced` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 26 → 27: add is_compliant, inactive_reason_code, inactive_reason_detail to vehicles")
                db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `is_compliant` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `inactive_reason_code` TEXT")
                db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `inactive_reason_detail` TEXT")
            }
        }

        // v28 → v29: enforce one-spool-one-PL rule at DB level.
        // Deduplicates existing rows (keeps max packing_list_spool_id per spool_id = last assignment),
        // then creates a UNIQUE index on spool_id so OnConflictStrategy.REPLACE auto-evicts the old
        // PL link whenever a spool is reassigned — no manual deleteBySpoolId required from this point.
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 28 → 29: deduplicate sms_packing_list_spool + UNIQUE(spool_id) + sync sms_spool.packing_list_id")
                db.execSQL("""
                    DELETE FROM sms_packing_list_spool
                    WHERE packing_list_spool_id NOT IN (
                        SELECT MAX(packing_list_spool_id)
                        FROM sms_packing_list_spool
                        GROUP BY spool_id
                    )
                """.trimIndent())
                db.execSQL("""
                    UPDATE sms_spool
                    SET packing_list_id = (
                        SELECT packing_list_id
                        FROM sms_packing_list_spool
                        WHERE sms_packing_list_spool.spool_id = sms_spool.spool_id
                    )
                    WHERE spool_id IN (SELECT spool_id FROM sms_packing_list_spool)
                """.trimIndent())
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_packing_list_spool_spool_id`
                    ON `sms_packing_list_spool` (`spool_id`)
                """.trimIndent())
            }
        }

        // v29 → v30: create sms_spool_location (GPS coordinates, max 2 rows per spool)
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 29 → 30: create sms_spool_location")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sms_spool_location` (
                        `location_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `spool_id` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `gps_accuracy_m` REAL,
                        `captured_at` TEXT NOT NULL,
                        `captured_by` TEXT,
                        `synced` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_sms_spool_location_spool_id`
                    ON `sms_spool_location` (`spool_id`)
                """.trimIndent())
            }
        }

        // v30 → v31: index sms_spool for the Inventario screen. With 100k+ spools the
        // unindexed project/active scan plus ORDER BY spool_code took over a minute.
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 30 → 31: index sms_spool(project_id, is_active, spool_code)")
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_sms_spool_project_id_is_active_spool_code`
                    ON `sms_spool` (`project_id`, `is_active`, `spool_code`)
                """.trimIndent())
            }
        }

        // v31 → v32: covering index for the Inventario zone-chart aggregation. The
        // GROUP BY over location columns otherwise heap-scans the whole 25MB+ table.
        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 31 → 32: covering location index on sms_spool")
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_sms_spool_project_id_is_active_packing_list_id_zone_position_id_sub_position_id`
                    ON `sms_spool` (`project_id`, `is_active`, `packing_list_id`, `zone`, `position_id`, `sub_position_id`)
                """.trimIndent())
            }
        }

        // v32 → v33: index sms_spool(spool_code) so QR-scan getByCode() hits an index
        // instead of a full table scan. The existing composite index starts with project_id
        // so it cannot serve a bare WHERE spool_code = ? predicate efficiently.
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 32 → 33: index sms_spool(spool_code) for QR scan lookup")
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_sms_spool_spool_code`
                    ON `sms_spool` (`spool_code`)
                """.trimIndent())
            }
        }

        // v33 → v34: store the terminal (device) code that created each incident on the card/detail screens.
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 33 → 34: add device_code to sms_incident")
                db.execSQL("ALTER TABLE `sms_incident` ADD COLUMN `device_code` TEXT")
            }
        }

        // v34 → v35: add sit_number (line-drawing sheet) and revision (fab/drawing revision)
        // to sms_spool for the JAFURAH physical tag QR format (e.g. 821-RP-25107-002-SP01-01A).
        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 34 → 35: add sit_number, revision to sms_spool")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `sit_number` TEXT")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `revision` TEXT")
            }
        }

        // v35 → v36: mirror the backend's sms_spool.scanned flag (PCA scan — set server-side
        // when a terminal uploads a spool location, see SyncService.uploadSmsSpoolLocations).
        // Read locally so HomeFragment KPIs / CreateSpoolFragment's Inventario list can filter
        // scanned-only spools without a separate destructive scannedOnly=true fetch that would
        // wipe the full local mirror needed for scan-recognition (see MainActivity.syncSmsData).
        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 35 → 36: add scanned to sms_spool")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `scanned` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v36 → v37: one-time cleanup for a since-fixed backend bug. GET /spools' `in_transit`
        // field was accidentally aliased to the QC `hold` flag (unrelated to shipping status —
        // see SmsRepository.cs), so every held-but-never-packed spool got pulled in as
        // in_transit=true with no packing list. A spool can't legitimately be "in transit"
        // without one, so this enforces that invariant once; going forward in_transit is
        // purely local (set by Send, cleared by Receive) and never comes from the server.
        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 36 → 37: clear orphan in_transit flags (no packing list)")
                db.execSQL("UPDATE `sms_spool` SET `in_transit` = 0 WHERE `packing_list_id` IS NULL AND `in_transit` = 1")
            }
        }

        // v27 → v28: link a spool to its sub-position (Laydown/Site sub-section).
        // Mirrors position_id: lives on sms_spool (bulk, for the zone chart) and on
        // sms_spool_status_flags (authoritative, read from GET status-flags in detail).
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migration 27 → 28: add sub_position_id to sms_spool and sms_spool_status_flags")
                db.execSQL("ALTER TABLE `sms_spool` ADD COLUMN `sub_position_id` INTEGER")
                db.execSQL("ALTER TABLE `sms_spool_status_flags` ADD COLUMN `sub_position_id` INTEGER")
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
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                        MIGRATION_36_37
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
