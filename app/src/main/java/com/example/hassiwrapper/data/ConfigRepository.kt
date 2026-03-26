package com.example.hassiwrapper.data

import com.example.hassiwrapper.data.db.dao.ConfigDao
import com.example.hassiwrapper.data.db.entities.ConfigEntity
import java.time.Instant

/**
 * Key-value config store backed by Room (replaces PWA's appDB.getConfig/setConfig).
 */
class ConfigRepository(private val dao: ConfigDao) {

    suspend fun get(key: String): String? = dao.getValue(key)

    suspend fun set(key: String, value: String?) {
        dao.insert(ConfigEntity(key, value, Instant.now().toString()))
    }

    suspend fun getInt(key: String): Int? = get(key)?.toIntOrNull()
    suspend fun setInt(key: String, value: Int) = set(key, value.toString())

    suspend fun remove(key: String) = dao.delete(key)
}
