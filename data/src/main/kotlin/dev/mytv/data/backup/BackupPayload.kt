package dev.mytv.data.backup

import kotlinx.serialization.Serializable

/**
 * Contenu (déchiffré) d'une sauvegarde. Les portails y sont identifiés par URL + utilisateur et
 * non par leur id interne : les ids changent d'un appareil à l'autre, pas les identifiants.
 */
@Serializable
data class BackupPayload(
    val version: Int = 1,
    val createdAtMs: Long,
    val activePortal: PortalRef? = null,
    val portals: List<BackupPortal> = emptyList(),
    val favorites: List<BackupFavorite> = emptyList(),
    val resumePoints: List<BackupResume> = emptyList(),
    val playerSettings: Map<String, String> = emptyMap(),
)

@Serializable
data class PortalRef(val serverUrl: String, val username: String)

@Serializable
data class BackupPortal(
    val name: String,
    val type: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val createdAtMs: Long,
)

@Serializable
data class BackupFavorite(val portal: PortalRef, val streamId: Long, val addedAtMs: Long)

@Serializable
data class BackupResume(
    val portal: PortalRef,
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val seriesId: Long? = null,
    val updatedAtMs: Long,
)
