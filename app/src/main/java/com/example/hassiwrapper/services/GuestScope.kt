package com.example.hassiwrapper.services

import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.normalizeDeviceLocation

/**
 * Hasta dónde llega la vista de un terminal GUEST: su zona y, dentro de ella, su GCP.
 *
 * Existía ya en tres sitios —el home, el Inventario y el mapa— y cada uno la resolvía por su
 * cuenta, con reglas que habían dejado de coincidir. El Inventario usaba el GCP fijado siempre;
 * el home y el mapa sólo estrechaban `if (subPosiciones.size > 1)`, pensado para no romper una
 * sub-posición degenerada. Con los datos reales de JAFURAH ese matiz se traducía en que un
 * terminal de taller (WORKSHOP tiene una única sub-posición) nunca se estrechaba, y en que las
 * tres pantallas podían enseñar poblaciones distintas del mismo terminal.
 *
 * Aquí está una sola vez, y las tres la llaman. Si mañana hay una cuarta pantalla de GUEST,
 * que llame a esto en vez de volver a deducirlo.
 */
object GuestScope {

    /**
     * [zone] es null cuando el terminal no tiene una zona válida configurada: eso NO significa
     * "enséñalo todo", significa "este terminal no está en ningún sitio" y quien lo reciba debe
     * pintar vacío. [subPositionId] null significa la zona entera, que es lo correcto para un
     * terminal de taller (WORKSHOP no tiene GCPs que distinguir).
     */
    data class Scope(val zone: String?, val subPositionId: Long?)

    fun isGuest(): Boolean =
        ProfileManager.currentUserRole() == ProfileManager.UserRole.GUEST

    /**
     * El alcance del terminal, se use el rol que se use. Los llamantes deciden si aplicarlo:
     * hoy sólo lo aplica GUEST, ADMIN/DEV siguen navegando libremente.
     *
     * El pin sólo cuenta si de verdad pertenece a la zona configurada. Un
     * `device_sub_position_id` que quedó de un `device_location` anterior apuntaría a un GCP de
     * otra zona y filtraría por algo que no tiene nada que ver: en JAFURAH, LAYDOWN/GCP5 y
     * SITE/GCP5 son dos filas distintas (12 y 15), así que un pin sin validar puede dejar la
     * pantalla en blanco sin explicar por qué.
     */
    suspend fun current(projectId: Int): Scope {
        val zone = normalizeDeviceLocation(ServiceLocator.configRepo.get("device_location"))
            ?: return Scope(null, null)
        val positionId = ServiceLocator.smsPositionDao.getByCode(zone)?.position_id
            ?: return Scope(zone, null)
        val pinned = ServiceLocator.configRepo.get("device_sub_position_id")?.toLongOrNull()
            ?: return Scope(zone, null)
        val sub = ServiceLocator.smsSubPositionDao.getById(pinned)
            ?.takeIf { it.position_id == positionId && it.is_active }
        return Scope(zone, sub?.sub_position_id)
    }
}
