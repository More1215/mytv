package dev.mytv.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Portail enregistré. Les tables de cache (chaînes, EPG...) des étapes suivantes
 * référenceront `id` pour isoler les données de chaque portail (multi-portails à l'étape 3).
 */
@Entity(
    tableName = "portals",
    indices = [Index(value = ["serverUrl", "username"], unique = true)],
)
data class PortalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val serverUrl: String,
    val username: String,
    /** Mot de passe chiffré par [dev.mytv.data.security.CredentialCipher]. Jamais en clair. */
    val encryptedPassword: String,
    val createdAtEpochMs: Long,
)
