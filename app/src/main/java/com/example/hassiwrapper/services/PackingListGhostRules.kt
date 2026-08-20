package com.example.hassiwrapper.services

import com.example.hassiwrapper.ServiceLocator
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Cuándo una Packing List con camión y 0 spools es de verdad un fantasma.
 *
 * Hay dos sitios que barren fantasmas (el merge de packing-lists de `MainActivity.syncSmsData` y
 * `PackingListsFragment.reconcileGhostPls`) y hasta ahora cada uno decidía por su cuenta — el
 * segundo, sin ninguna condición de edad. "Camión puesto y 0 spools" es un estado por el que pasa
 * TODA lista recién creada: el POST de creación crea la cabecera y el manifiesto viaja aparte, así
 * que durante esa ventana una lista nueva y una abandonada son indistinguibles mirando sólo eso.
 * De ahí salía el fallo que reportó obra: un PL de 17 spools que nace y se va directo a Históricos,
 * sin haber estado nunca en tránsito.
 *
 * La regla vive aquí, una sola vez, con las dos protecciones que faltaban:
 *  - la edad se ancla también en `created_at`, no sólo en `updated_at` (que una lista recién creada
 *    NO tiene: el backend sólo lo estampa en los UPDATE, así que la ventana de gracia no existía
 *    justo para el caso que tenía que proteger);
 *  - una lista con enlaces spool↔lista aún sin subir no es un fantasma: su 0 spools significa
 *    "todavía no ha llegado al servidor", no "no lleva nada". Ese flag se limpia solo en cuanto
 *    el manifiesto sube, así que no deja pestillos.
 */
object PackingListGhostRules {

    /** Cuánto le damos a una lista con camión y 0 spools antes de darla por abandonada. */
    const val GRACE_MINUTES = 15L

    /**
     * Minutos desde [timestamp], o null si está vacío/no se puede parsear. Acepta tanto ISO-8601
     * con zona (`updated_at`/`created_at` del servidor) como un `LocalDateTime.toString()` pelado
     * (escrituras locales), porque los llamantes comparan una mezcla de ambos. Null = "edad
     * desconocida": el llamante decide, no se asume ni reciente ni antigua.
     */
    fun minutesSince(timestamp: String?): Long? {
        val trimmed = timestamp?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            val instant = try {
                Instant.parse(trimmed)
            } catch (_: Exception) {
                LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC)
            }
            Duration.between(instant, Instant.now()).toMinutes()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Edad de la lista en minutos. Coge la marca de tiempo MÁS RECIENTE que se conozca de ella,
     * local o del servidor, y cae a `created_at` cuando no hay ningún `updated_at` — que es
     * exactamente el caso de una lista recién creada.
     *
     * Una marca local va en hora local sin zona y se parsea como UTC, así que en un terminal al
     * este de Greenwich (JAFURAH, UTC+3) sale "en el futuro" y da minutos negativos. Es el error
     * que queremos: hacia "recién tocada", nunca hacia "abandonada".
     */
    fun ageMinutes(
        localUpdatedAt: String?, serverUpdatedAt: String?,
        localCreatedAt: String?, serverCreatedAt: String?
    ): Long? {
        val anchor = maxOf(
            localUpdatedAt.orEmpty(), serverUpdatedAt.orEmpty(),
            localCreatedAt.orEmpty(), serverCreatedAt.orEmpty()
        ).ifEmpty { null }
        return minutesSince(anchor)
    }

    /**
     * `true` cuando la lista [packingListId] es un fantasma de verdad: sigue con camión, no le
     * queda ningún spool, no tiene carga local pendiente de subir, y ya es lo bastante vieja como
     * para que "vacía" no pueda ser un estado transitorio.
     *
     * [localSpoolCount] lo pasa el llamante porque cada uno ya lo tiene contado a su manera.
     */
    suspend fun isGhost(
        packingListId: Long,
        vehicleId: Long?,
        localSpoolCount: Int,
        ageMinutes: Long?
    ): Boolean {
        if (vehicleId == null || localSpoolCount != 0) return false
        // Enlaces escritos aquí y aún sin confirmar: la carga existe, sólo que todavía no ha
        // llegado. Nunca puede provocar un fantasma, sólo evitarlo.
        if (ServiceLocator.smsPackingListSpoolDao.countUnsyncedByPackingList(packingListId) > 0) return false
        // Edad desconocida sigue contando como vieja (filas heredadas sin ninguna marca de
        // tiempo): con created_at en el ancla esto ya sólo alcanza a las que de verdad no
        // tienen nada, que son las que interesa seguir curando.
        return ageMinutes == null || ageMinutes >= GRACE_MINUTES
    }
}
