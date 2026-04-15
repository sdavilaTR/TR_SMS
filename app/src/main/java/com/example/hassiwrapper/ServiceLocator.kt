package com.example.hassiwrapper

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.AtlasDatabase
import com.example.hassiwrapper.network.ApiClient
import com.example.hassiwrapper.network.AuthRepository
import com.example.hassiwrapper.scanner.DataWedgeManager
import com.example.hassiwrapper.services.*

/**
 * Simple service locator — provides singletons of all services.
 * Lightweight alternative to Dagger/Hilt for this project size.
 */
object ServiceLocator {

    private val db: AtlasDatabase get() = AtlasApp.instance.database

    val configRepo: ConfigRepository by lazy { ConfigRepository(db.configDao()) }
    val authRepo: AuthRepository by lazy { AuthRepository(configRepo) }
    val apiClient: ApiClient by lazy { ApiClient(configRepo, authRepo) }

    val rulesService: RulesService by lazy { RulesService() }
    val incidentService: IncidentService by lazy { IncidentService(db.incidentDao(), configRepo) }
    val observationService: ObservationService by lazy {
        ObservationService(db.hseObservationDao(), configRepo, db.hseObservationPhotoDao())
    }

    val clockingService: ClockingService by lazy {
        ClockingService(
            db.personDao(), db.accessLogDao(), db.workSessionDao(),
            incidentService, rulesService, configRepo,
            db.vehicleDao()
        )
    }

    val heartbeatManager: HeartbeatManager by lazy {
        HeartbeatManager(AtlasApp.instance, apiClient, configRepo)
    }

    val syncService: SyncService by lazy {
        SyncService(
            apiClient, configRepo,
            db.projectDao(), db.zoneDao(), db.contractorDao(),
            db.personDao(), db.accessPointDao(), db.cryptoKeyDao(),
            db.accessLogDao(), db.incidentDao(), db.workSessionDao(),
            db.pendingPhotoDao(), db.hseObservationDao(),
            db.hseObservationPhotoDao(),
            heartbeatManager,
            db.vehicleDao(),
            authRepo,
            db.trainingComplianceDao(),
            db.documentComplianceDao()
        )
    }

    fun dataWedgeManager(): DataWedgeManager = DataWedgeManager(AtlasApp.instance)

    // Expose DAOs for direct queries in UI
    val accessLogDao get() = db.accessLogDao()
    val personDao get() = db.personDao()
    val contractorDao get() = db.contractorDao()
    val projectDao get() = db.projectDao()
    val incidentDao get() = db.incidentDao()
    val pendingPhotoDao get() = db.pendingPhotoDao()
    val hseObservationDao get() = db.hseObservationDao()
    val hseObservationPhotoDao get() = db.hseObservationPhotoDao()
    val vehicleDao get() = db.vehicleDao()
    val trainingComplianceDao get() = db.trainingComplianceDao()
    val documentComplianceDao get() = db.documentComplianceDao()
}
