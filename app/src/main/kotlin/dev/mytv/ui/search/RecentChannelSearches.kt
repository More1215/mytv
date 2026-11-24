package dev.mytv.ui.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dernières chaînes ouvertes depuis la recherche, par portail (8 au plus, la plus récente d'abord).
 * Affichées dans Live TV quand le focus est sur « Rechercher ». Quelques nombres dans un fichier
 * de préférences : rien à charger au démarrage.
 */
@Singleton
class RecentChannelSearches @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("recent_channel_searches", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    /** Change à chaque ajout : de quoi relire la liste. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(portalId: Long): List<Long> =
        prefs.getString(key(portalId), null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    fun add(portalId: Long, streamId: Long) {
        val ids = (listOf(streamId) + get(portalId).filter { it != streamId }).take(MAX)
        prefs.edit().putString(key(portalId), ids.joinToString(",")).apply()
        _version.value++
    }

    private fun key(portalId: Long) = "p$portalId"

    private companion object {
        const val MAX = 8
    }
}
