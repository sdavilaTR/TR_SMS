package com.example.hassiwrapper.services

import android.os.Build
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.SmsAuditLogDao
import com.example.hassiwrapper.data.db.entities.SmsAuditLogEntity

class AuditLogService(
    private val configRepo: ConfigRepository,
    private val dao: SmsAuditLogDao
) {
    companion object {
        const val SPOOL_CREADO           = "SPOOL_CREADO"
        const val SPOOL_ELIMINADO        = "SPOOL_ELIMINADO"
        const val SPOOL_ELIMINADO_HARD   = "SPOOL_ELIMINADO_HARD"
        const val PL_CREADO              = "PL_CREADO"
        const val PL_EDITADO             = "PL_EDITADO"
        const val PL_ELIMINADO           = "PL_ELIMINADO"
        const val VEHICULO_CREADO        = "VEHICULO_CREADO"
        const val VEHICULO_EDITADO       = "VEHICULO_EDITADO"
        const val VEHICULO_ELIMINADO     = "VEHICULO_ELIMINADO"
        const val TRANSFERENCIA_ENVIADA  = "TRANSFERENCIA_ENVIADA"
        const val TRANSFERENCIA_RECIBIDA = "TRANSFERENCIA_RECIBIDA"

        const val ENTITY_SPOOL         = "SPOOL"
        const val ENTITY_PL            = "PL"
        const val ENTITY_VEHICULO      = "VEHICULO"
        const val ENTITY_TRANSFERENCIA = "TRANSFERENCIA"
    }

    suspend fun log(
        actionType: String,
        entityType: String,
        entityId: Long,
        entityName: String,
        detail: String? = null,
        projectId: Int? = null
    ) {
        val pid = projectId ?: configRepo.getInt("selected_project_id") ?: 6
        val terminal = configRepo.get("device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        dao.insert(
            SmsAuditLogEntity(
                project_id    = pid,
                action_type   = actionType,
                entity_type   = entityType,
                entity_id     = entityId,
                entity_name   = entityName,
                detail        = detail,
                terminal_name = terminal,
                timestamp     = System.currentTimeMillis()
            )
        )
    }
}
