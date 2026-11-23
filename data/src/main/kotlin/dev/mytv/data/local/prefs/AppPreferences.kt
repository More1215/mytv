package dev.mytv.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Petites préférences clé/valeur. Les clés liées à un portail sont suffixées par son id
 * (multi-portails à l'étape 3).
 */
@Singleton
class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val activePortalId: Flow<Long?> = dataStore.data.map { it[KEY_ACTIVE_PORTAL] }

    suspend fun setActivePortalId(id: Long) {
        dataStore.edit { it[KEY_ACTIVE_PORTAL] = id }
    }

    // --- Horodatages de synchronisation
    suspend fun lastCatalogSync(portalId: Long): Long? = dataStore.data.first()[catalogSyncKey(portalId)]
    suspend fun setLastCatalogSync(portalId: Long, ms: Long) { dataStore.edit { it[catalogSyncKey(portalId)] = ms } }

    suspend fun lastEpgSync(portalId: Long): Long? = dataStore.data.first()[epgSyncKey(portalId)]

    suspend fun lastVodSync(portalId: Long): Long? = dataStore.data.first()[vodSyncKey(portalId)]
    suspend fun setLastVodSync(portalId: Long, ms: Long) { dataStore.edit { it[vodSyncKey(portalId)] = ms } }

    // --- Génération EPG active (voir EpgProgramEntity)
    fun epgGeneration(portalId: Long): Flow<Long> = dataStore.data.map { it[epgGenerationKey(portalId)] ?: 0L }

    /** Bascule sur la nouvelle génération et note l'heure de synchro, en une seule écriture. */
    suspend fun commitEpgGeneration(portalId: Long, generation: Long, syncedAtMs: Long) {
        dataStore.edit {
            it[epgGenerationKey(portalId)] = generation
            it[epgSyncKey(portalId)] = syncedAtMs
        }
    }

    // --- Dernière chaîne regardée : "streamId|categoryId"
    suspend fun lastChannel(portalId: Long): Pair<Long, String>? {
        val raw = dataStore.data.first()[lastChannelKey(portalId)] ?: return null
        val streamId = raw.substringBefore('|').toLongOrNull() ?: return null
        return streamId to raw.substringAfter('|')
    }

    suspend fun setLastChannel(portalId: Long, streamId: Long, categoryId: String) {
        dataStore.edit { it[lastChannelKey(portalId)] = "$streamId|$categoryId" }
    }

    private companion object {
        val KEY_ACTIVE_PORTAL = longPreferencesKey("active_portal_id")
        fun catalogSyncKey(id: Long) = longPreferencesKey("catalog_sync_$id")
        fun epgSyncKey(id: Long) = longPreferencesKey("epg_sync_$id")
        fun vodSyncKey(id: Long) = longPreferencesKey("vod_sync_$id")
        fun epgGenerationKey(id: Long) = longPreferencesKey("epg_generation_$id")
        fun lastChannelKey(id: Long) = stringPreferencesKey("last_channel_$id")
    }
}
