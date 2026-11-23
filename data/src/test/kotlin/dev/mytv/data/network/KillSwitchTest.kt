package dev.mytv.data.network

import dev.mytv.data.error.runCatchingApp
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Le kill switch doit garantir qu'AUCUNE connexion (ni DNS, ni socket) ne part sans VPN.
 * On le vérifie avec un vrai serveur local : sans VPN, il ne doit recevoir aucune requête.
 */
class KillSwitchTest {
    private lateinit var server: MockWebServer
    private var vpnActive = false
    private val dnsLookups = mutableListOf<String>()

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(KillSwitchInterceptor { !vpnActive })
            // DNS espion : on vérifie qu'aucune résolution n'est tentée quand c'est bloqué.
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    dnsLookups += hostname
                    return Dns.SYSTEM.lookup(hostname)
                }
            })
            .build()
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.close()

    private fun call() = client.newCall(Request.Builder().url(server.url("/live/u/p/1.ts")).build()).execute().use { it.code }

    @Test fun `sans vpn - aucune requete, aucun dns, erreur VpnRequired`() = runTest {
        vpnActive = false
        val result = runCatchingApp { call() }
        assertEquals(AppResult.Failure(AppError.VpnRequired), result)
        assertEquals(0, server.requestCount)
        assertTrue(dnsLookups.isEmpty())
    }

    @Test fun `avec vpn - la requete passe`() {
        vpnActive = true
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        assertEquals(200, call())
        assertEquals(1, server.requestCount)
    }

    @Test fun `vpn coupe entre deux requetes - la seconde est bloquee`() {
        vpnActive = true
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        call()
        vpnActive = false
        runCatching { call() }.also { assertTrue(it.exceptionOrNull() is VpnRequiredException) }
        assertEquals(1, server.requestCount)
    }

    /** Résolveur chiffré factice qui échoue toujours (DoH injoignable). */
    private val failingDoh = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
    }

    @Test fun `dns chiffre - pas de repli en clair quand bloque`() {
        val dns = EncryptedDns(listOf(failingDoh, failingDoh), isBlocked = { true })
        val error = runCatching { dns.lookup("exemple.com") }.exceptionOrNull()
        assertTrue("attendu VpnRequiredException, obtenu $error", error is VpnRequiredException)
    }

    @Test fun `dns chiffre - premier resolveur en panne, le second repond`() {
        val ok = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("192.0.2.1"))
        }
        val dns = EncryptedDns(listOf(failingDoh, ok), isBlocked = { false })
        assertEquals("192.0.2.1", dns.lookup("exemple.com").single().hostAddress)
        assertEquals(EncryptedDns.Resolution.ENCRYPTED, dns.lastResolution)
    }
}
