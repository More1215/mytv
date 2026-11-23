package dev.mytv.data.remote.xtream.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Réponse de `player_api.php?username=..&password=..` sans paramètre `action`. */
@Serializable
data class AuthResponseDto(
    @SerialName("user_info") val userInfo: UserInfoDto? = null,
    @SerialName("server_info") val serverInfo: ServerInfoDto? = null,
)

@Serializable
data class UserInfoDto(
    @Serializable(FlexibleStringSerializer::class) val username: String? = null,
    /** 1 = authentifié, 0 = identifiants refusés (souvent renvoyé avec un HTTP 200). */
    @Serializable(FlexibleStringSerializer::class) val auth: String? = null,
    /** "Active", "Expired", "Banned", "Disabled"... */
    @Serializable(FlexibleStringSerializer::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(FlexibleStringSerializer::class) val expDate: String? = null,
    @SerialName("is_trial") @Serializable(FlexibleStringSerializer::class) val isTrial: String? = null,
    @SerialName("active_cons") @Serializable(FlexibleStringSerializer::class) val activeCons: String? = null,
    @SerialName("max_connections") @Serializable(FlexibleStringSerializer::class) val maxConnections: String? = null,
    @SerialName("allowed_output_formats") @Serializable(FlexibleStringListSerializer::class)
    val allowedOutputFormats: List<String> = emptyList(),
)

@Serializable
data class ServerInfoDto(
    @Serializable(FlexibleStringSerializer::class) val url: String? = null,
    @Serializable(FlexibleStringSerializer::class) val port: String? = null,
    @SerialName("https_port") @Serializable(FlexibleStringSerializer::class) val httpsPort: String? = null,
    @SerialName("server_protocol") @Serializable(FlexibleStringSerializer::class) val serverProtocol: String? = null,
    @Serializable(FlexibleStringSerializer::class) val timezone: String? = null,
    @SerialName("timestamp_now") @Serializable(FlexibleStringSerializer::class) val timestampNow: String? = null,
)
