package dev.mytv.domain.model

/** Catégorie de chaînes. Les catégories spéciales (Favoris, Toutes) sont générées localement. */
data class Category(
    val id: String,
    val name: String,
    val kind: Kind = Kind.PORTAL,
) {
    enum class Kind { FAVORITES, ALL, PORTAL }

    companion object {
        const val FAVORITES_ID = "__favorites__"
        const val ALL_ID = "__all__"
    }
}

data class Channel(
    val streamId: Long,
    /** Numéro de chaîne fourni par le portail (champ `num`), utilisé pour le zapping par numéro. */
    val number: Int,
    val name: String,
    val logoUrl: String?,
    val categoryId: String?,
    /** Identifiant de la chaîne dans le guide XMLTV ; null si le portail n'a pas d'EPG pour elle. */
    val epgChannelId: String?,
    val isFavorite: Boolean,
)

data class EpgProgram(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
) {
    fun progressAt(nowMs: Long): Float =
        if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/** Programme en cours et suivant d'une chaîne. */
data class NowNext(val now: EpgProgram?, val next: EpgProgram?) {
    companion object {
        val EMPTY = NowNext(null, null)
    }
}

/** Dernière chaîne regardée, relancée au démarrage. */
data class LastChannel(val streamId: Long, val categoryId: String)

/** État de la synchronisation du catalogue, affiché discrètement dans l'UI. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Failed(val error: dev.mytv.domain.result.AppError) : SyncState
}
