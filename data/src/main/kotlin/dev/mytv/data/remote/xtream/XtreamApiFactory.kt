package dev.mytv.data.remote.xtream

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crée (et met en cache) une instance Retrofit par URL de serveur.
 * Toutes partagent le même OkHttpClient, donc le même pool de connexions et le même cache.
 */
@Singleton
class XtreamApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val cache = ConcurrentHashMap<String, XtreamApi>()

    fun create(serverUrl: String): XtreamApi = cache.getOrPut(serverUrl) {
        Retrofit.Builder()
            // Retrofit exige un "/" final pour résoudre "player_api.php" sous un éventuel sous-chemin.
            .baseUrl("$serverUrl/")
            .client(okHttpClient)
            // Certains panels répondent avec Content-Type text/html : on force le parsing JSON.
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(XtreamApi::class.java)
    }
}
