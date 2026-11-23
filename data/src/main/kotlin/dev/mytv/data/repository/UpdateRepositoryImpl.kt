package dev.mytv.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mytv.data.di.XmltvClient
import dev.mytv.data.error.runCatchingApp
import dev.mytv.domain.repository.AppUpdate
import dev.mytv.domain.repository.UpdateRepository
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Mises à jour via GitHub Releases (dépôt public). Tout passe par le client OkHttp de l'appli :
 * DNS chiffré, et kill switch si l'utilisateur exige un VPN.
 *
 * Sobre pour les petites machines : une seule petite requête JSON, puis l'APK (~3 Mo) écrit
 * directement sur disque par blocs, jamais entièrement en mémoire. Les anciens APK sont effacés.
 */
@Singleton
class UpdateRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    @param:XmltvClient private val downloadClient: OkHttpClient,
    private val json: Json,
) : UpdateRepository {

    override suspend fun latest(): AppResult<AppUpdate?> = withContext(Dispatchers.IO) {
        runCatchingApp {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use null // aucune version publiée
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val root = json.parseToJsonElement(response.body.string()).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@use null
                val apk = (root["assets"] as? JsonArray)
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it.string("name") == APK_NAME }
                    ?.string("browser_download_url")
                    ?: return@use null
                AppUpdate(version = tag.removePrefix("v"), downloadUrl = apk, notes = root.string("body"))
            }
        }
    }

    override suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): AppResult<String> =
        withContext(Dispatchers.IO) {
            runCatchingApp {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // anciens téléchargements
                val target = File(dir, "mytv-${update.version}.apk")
                val partial = File(dir, "${target.name}.part")
                downloadClient.newCall(Request.Builder().url(update.downloadUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val total = response.body.contentLength()
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var done = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                onProgress(if (total > 0) done.toFloat() / total else -1f)
                            }
                        }
                    }
                }
                if (!partial.renameTo(target)) throw IOException("Écriture impossible")
                target.absolutePath
            }
        }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/More1215/mytv/releases/latest"
        const val APK_NAME = "mytv.apk"
    }
}
