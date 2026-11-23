package dev.mytv.data.remote.xtream.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Élément de `action=get_live_categories`. */
@Serializable
data class LiveCategoryDto(
    @SerialName("category_id") @Serializable(FlexibleStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(FlexibleStringSerializer::class) val categoryName: String? = null,
)

/** Élément de `action=get_live_streams`. Seuls les champs utiles sont déclarés. */
@Serializable
data class LiveStreamDto(
    @Serializable(FlexibleStringSerializer::class) val num: String? = null,
    @Serializable(FlexibleStringSerializer::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(FlexibleStringSerializer::class) val streamId: String? = null,
    @SerialName("stream_icon") @Serializable(FlexibleStringSerializer::class) val streamIcon: String? = null,
    @SerialName("epg_channel_id") @Serializable(FlexibleStringSerializer::class) val epgChannelId: String? = null,
    @SerialName("category_id") @Serializable(FlexibleStringSerializer::class) val categoryId: String? = null,
)

/** Réponse de `action=get_short_epg`. Titres et descriptions sont encodés en Base64. */
@Serializable
data class ShortEpgDto(
    @SerialName("epg_listings") val listings: List<ShortEpgListingDto> = emptyList(),
)

@Serializable
data class ShortEpgListingDto(
    @Serializable(FlexibleStringSerializer::class) val title: String? = null,
    @Serializable(FlexibleStringSerializer::class) val description: String? = null,
    @SerialName("start_timestamp") @Serializable(FlexibleStringSerializer::class) val startTimestamp: String? = null,
    @SerialName("stop_timestamp") @Serializable(FlexibleStringSerializer::class) val stopTimestamp: String? = null,
)
