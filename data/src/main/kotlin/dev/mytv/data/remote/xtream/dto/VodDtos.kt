package dev.mytv.data.remote.xtream.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Élément de `action=get_vod_streams`. */
@Serializable
data class VodStreamDto(
    @Serializable(FlexibleStringSerializer::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(FlexibleStringSerializer::class) val streamId: String? = null,
    @SerialName("stream_icon") @Serializable(FlexibleStringSerializer::class) val streamIcon: String? = null,
    @Serializable(FlexibleStringSerializer::class) val rating: String? = null,
    @SerialName("category_id") @Serializable(FlexibleStringSerializer::class) val categoryId: String? = null,
    @SerialName("container_extension") @Serializable(FlexibleStringSerializer::class) val containerExtension: String? = null,
    @Serializable(FlexibleStringSerializer::class) val added: String? = null,
)

/** Élément de `action=get_series`. */
@Serializable
data class SeriesDto(
    @SerialName("series_id") @Serializable(FlexibleStringSerializer::class) val seriesId: String? = null,
    @Serializable(FlexibleStringSerializer::class) val name: String? = null,
    @Serializable(FlexibleStringSerializer::class) val cover: String? = null,
    @Serializable(FlexibleStringSerializer::class) val rating: String? = null,
    @SerialName("category_id") @Serializable(FlexibleStringSerializer::class) val categoryId: String? = null,
)

/*
 * Les réponses get_vod_info / get_series_info sont les plus incohérentes d'un panel à l'autre
 * (objet vide remplacé par [], épisodes en tableau ou en objet indexé par saison, images en
 * chaîne ou en tableau...). On les lit donc en JsonElement avec ces petits accesseurs tolérants
 * plutôt qu'avec un schéma figé qui casserait au premier écart.
 */

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject?.str(key: String): String? {
    val value = this?.get(key) ?: return null
    return when (value) {
        is JsonPrimitive -> value.content.takeIf { value !is JsonNull && it.isNotBlank() && it != "null" }?.trim()
        // Certains panels renvoient les images sous forme de tableau : on prend la première.
        is JsonArray -> (value.firstOrNull() as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        else -> null
    }
}

internal fun JsonObject?.long(key: String): Long? = str(key)?.toLongOrNull() ?: str(key)?.toDoubleOrNull()?.toLong()
internal fun JsonObject?.double(key: String): Double? = str(key)?.replace(',', '.')?.toDoubleOrNull()

/** Liste d'objets quel que soit le format : tableau, ou objet dont les valeurs sont des objets/tableaux. */
internal fun JsonElement?.objects(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> values.flatMap { v -> (v as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOfNotNull(v as? JsonObject) }
    else -> emptyList()
}

/** "01:42:10" ou "6130" -> secondes. */
internal fun parseDurationSec(raw: String?): Long? {
    val value = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    value.toLongOrNull()?.let { return it.takeIf { s -> s > 0 } }
    val parts = value.split(':').mapNotNull { it.trim().toLongOrNull() }
    if (parts.isEmpty()) return null
    return parts.fold(0L) { acc, p -> acc * 60 + p }.takeIf { it > 0 }
}
