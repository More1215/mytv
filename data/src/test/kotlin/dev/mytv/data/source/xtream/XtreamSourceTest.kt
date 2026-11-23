package dev.mytv.data.source.xtream

import dev.mytv.data.di.DataProvidesModule
import dev.mytv.data.error.runCatchingApp
import dev.mytv.data.remote.xtream.XtreamApiFactory
import dev.mytv.domain.model.AccountStatus
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Vérifie le parsing tolérant et la traduction des erreurs sur des réponses Xtream réalistes. */
class XtreamSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: XtreamSource

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val json = DataProvidesModule.provideJson()
        val factory = XtreamApiFactory(client, json)
        val url = server.url("/").toString().trimEnd('/')
        source = XtreamSource(PortalCredentials(url, "user", "p@ss/word"), "ts", factory, json, client)
    }

    @Test fun `liste des chaines avec types mixtes et entrees invalides`() = runTest {
        enqueue(body = """
            [
              {"num":1,"name":"TF Test","stream_id":101,"stream_icon":"http://x/l.png","epg_channel_id":"tf.fr","category_id":"3"},
              {"num":"2","name":" Deux ","stream_id":"102","stream_icon":"","epg_channel_id":"","category_id":3},
              {"name":"Sans id"},
              {"num":null,"name":"","stream_id":103,"stream_icon":null,"epg_channel_id":null,"category_id":null}
            ]
        """.trimIndent())
        val channels = source.liveChannels()
        assertEquals(listOf(101L, 102L, 103L), channels.map { it.streamId })
        assertEquals("Deux", channels[1].name)
        assertEquals("3", channels[1].categoryId)
        assertEquals(null, channels[1].logoUrl)
        assertEquals(null, channels[1].epgChannelId)
        assertEquals(null, channels[2].number)
        assertEquals("Chaîne 103", channels[2].name)
    }

    @Test fun `url de flux avec identifiants encodes`() {
        val url = source.liveStreamUrl(42)
        assertTrue(url, url.endsWith("/live/user/p@ss%2Fword/42.ts"))
    }

    @After fun tearDown() = server.close()

    private fun enqueue(code: Int = 200, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun `compte actif avec champs de types mixtes`() = runTest {
        enqueue(body = """
            {"user_info":{"username":"user","auth":1,"status":"Active","exp_date":"1893456000",
             "is_trial":"0","active_cons":0,"max_connections":"2","allowed_output_formats":["m3u8","ts"]},
             "server_info":{"timezone":"Europe/Paris","port":"8080"}}
        """.trimIndent())
        val info = source.authenticate()
        assertEquals(AccountStatus.ACTIVE, info.status)
        assertEquals(1893456000L, info.expiresAtEpochSec)
        assertEquals(2, info.maxConnections)
        assertEquals(listOf("m3u8", "ts"), info.allowedOutputFormats)
        val request = server.takeRequest()
        // Le mot de passe contient "@" et "/" : il doit être encodé dans la query string.
        assertTrue(request.target, request.target.contains("player_api.php?username=user&password=p%40ss%2Fword"))
    }

    @Test fun `exp_date null signifie illimite`() = runTest {
        enqueue(body = """{"user_info":{"auth":"1","status":"Active","exp_date":null,"allowed_output_formats":{}}}""")
        val info = source.authenticate()
        assertEquals(null, info.expiresAtEpochSec)
        assertEquals(emptyList<String>(), info.allowedOutputFormats)
    }

    @Test fun `auth 0 renvoie identifiants invalides`() = runTest {
        enqueue(body = """{"user_info":{"auth":0}}""")
        assertEquals(AppResult.Failure(AppError.InvalidCredentials), runCatchingApp { source.authenticate() })
    }

    @Test fun `http 403 renvoie identifiants invalides`() = runTest {
        enqueue(code = 403, body = "")
        assertEquals(AppResult.Failure(AppError.InvalidCredentials), runCatchingApp { source.authenticate() })
    }

    @Test fun `page html renvoie reponse invalide`() = runTest {
        enqueue(body = "<html><body>Maintenance</body></html>")
        assertEquals(AppResult.Failure(AppError.InvalidResponse), runCatchingApp { source.authenticate() })
    }

    @Test fun `json sans user_info renvoie reponse invalide`() = runTest {
        enqueue(body = "[]")
        assertEquals(AppResult.Failure(AppError.InvalidResponse), runCatchingApp { source.authenticate() })
    }

    @Test fun `serveur muet renvoie timeout`() = runTest {
        server.enqueue(MockResponse.Builder().body("{}").headersDelay(3, TimeUnit.SECONDS).build())
        assertEquals(AppResult.Failure(AppError.Timeout), runCatchingApp { source.authenticate() })
    }
}
