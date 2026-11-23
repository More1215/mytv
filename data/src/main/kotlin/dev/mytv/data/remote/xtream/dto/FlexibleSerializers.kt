package dev.mytv.data.remote.xtream.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Les panels Xtream ne respectent aucun schéma stable : selon la version et le revendeur,
 * un même champ arrive en `"1"`, `1`, `true`, `null` ou `""`.
 * Ce sérialiseur accepte tout et produit une String? ; la conversion en Int/Long/Boolean
 * se fait ensuite dans les mappers, avec des valeurs par défaut sûres.
 */
object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = json.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() && it != "null" }
            is JsonArray, is JsonObject -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** Accepte une liste JSON, ou n'importe quoi d'autre (objet vide, "", null) traité comme liste vide. */
object FlexibleStringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> {
        val json = decoder as? JsonDecoder ?: return emptyList()
        val element = json.decodeJsonElement()
        return (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString(","))
    }
}

internal fun String?.toIntOrNullSafe(): Int? = this?.trim()?.toIntOrNull()
internal fun String?.toLongOrNullSafe(): Long? = this?.trim()?.toLongOrNull()
internal fun String?.toBooleanFlexible(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes" -> true
    else -> false
}
