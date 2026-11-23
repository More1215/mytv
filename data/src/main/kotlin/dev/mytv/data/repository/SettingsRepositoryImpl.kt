package dev.mytv.data.repository

import dev.mytv.data.local.prefs.AppPreferences
import dev.mytv.domain.model.PlayerSettings
import dev.mytv.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val preferences: AppPreferences,
    @param:Named(APP_SCOPE) appScope: CoroutineScope,
) : SettingsRepository {

    override val playerSettings: StateFlow<PlayerSettings> = preferences.playerSettingsRaw
        .map { it.toSettings() }
        .stateIn(appScope, SharingStarted.Eagerly, PlayerSettings())

    override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        preferences.setPlayerSettingsRaw(transform(playerSettings.value).toRaw())
    }

    companion object {
        const val APP_SCOPE = "appScope"

        /** Lecture tolérante : une valeur inconnue (ancienne version, sauvegarde) retombe sur le défaut. */
        fun Map<String, String>.toSettings(): PlayerSettings {
            val d = PlayerSettings()
            return PlayerSettings(
                liveFormat = enumOrDefault(get(AppPreferences.PLAYER_LIVE_FORMAT), d.liveFormat),
                buffer = enumOrDefault(get(AppPreferences.PLAYER_BUFFER), d.buffer),
                decoder = enumOrDefault(get(AppPreferences.PLAYER_DECODER), d.decoder),
                resumeLastChannel = get(AppPreferences.PLAYER_RESUME_LAST)?.toBooleanStrictOrNull() ?: d.resumeLastChannel,
            )
        }

        fun PlayerSettings.toRaw(): Map<String, String> = mapOf(
            AppPreferences.PLAYER_LIVE_FORMAT to liveFormat.name,
            AppPreferences.PLAYER_BUFFER to buffer.name,
            AppPreferences.PLAYER_DECODER to decoder.name,
            AppPreferences.PLAYER_RESUME_LAST to resumeLastChannel.toString(),
        )

        private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
