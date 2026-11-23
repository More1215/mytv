package dev.mytv.domain.repository

import dev.mytv.domain.model.PlayerSettings
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    /** Valeur toujours disponible (chargée au démarrage), pour que le lecteur la lise sans attendre. */
    val playerSettings: StateFlow<PlayerSettings>

    suspend fun update(transform: (PlayerSettings) -> PlayerSettings)
}
