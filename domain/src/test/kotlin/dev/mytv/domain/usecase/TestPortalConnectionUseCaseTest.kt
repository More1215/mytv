package dev.mytv.domain.usecase

import dev.mytv.domain.model.AccountInfo
import dev.mytv.domain.model.AccountStatus
import dev.mytv.domain.model.Portal
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.repository.PortalRepository
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPortalConnectionUseCaseTest {

    private fun account(httpsPort: Int? = null, status: AccountStatus = AccountStatus.ACTIVE) = AccountInfo(
        username = "u", status = status, expiresAtEpochSec = null, isTrial = false,
        activeConnections = null, maxConnections = null, allowedOutputFormats = emptyList(),
        serverTimezone = null, httpsPort = httpsPort,
    )

    /** Faux repository : répond selon l'URL testée. */
    private class FakeRepo(val answers: Map<String, AppResult<AccountInfo>>) : PortalRepository {
        val tested = mutableListOf<String>()
        override fun observeActivePortal(): Flow<Portal?> = emptyFlow()
        override suspend fun testConnection(credentials: PortalCredentials): AppResult<AccountInfo> {
            tested += credentials.serverUrl
            return answers[credentials.serverUrl] ?: AppResult.Failure(AppError.Unreachable)
        }
        override suspend fun savePortal(name: String, credentials: PortalCredentials) = error("non utilisé")
        override suspend fun tryUpgradeToHttps(portalId: Long) = false
    }

    private val input = PortalCredentials("exemple.com:8080", " u ", "p")

    @Test fun `bascule en https si le port annonce fonctionne`() = runTest {
        val repo = FakeRepo(
            mapOf(
                "http://exemple.com:8080" to AppResult.Success(account(httpsPort = 8443)),
                "https://exemple.com:8443" to AppResult.Success(account(httpsPort = 8443)),
            ),
        )
        val result = TestPortalConnectionUseCase(repo)(input) as AppResult.Success
        assertTrue(result.value.isEncrypted)
        assertEquals("https://exemple.com:8443", result.value.credentials.serverUrl)
        assertEquals("u", result.value.credentials.username)
    }

    @Test fun `reste en http si https echoue`() = runTest {
        val repo = FakeRepo(mapOf("http://exemple.com:8080" to AppResult.Success(account(httpsPort = 8443))))
        val result = TestPortalConnectionUseCase(repo)(input) as AppResult.Success
        assertFalse(result.value.isEncrypted)
        assertEquals(listOf("http://exemple.com:8080", "https://exemple.com:8443"), repo.tested)
    }

    @Test fun `pas de tentative https sans port annonce`() = runTest {
        val repo = FakeRepo(mapOf("http://exemple.com:8080" to AppResult.Success(account(httpsPort = null))))
        TestPortalConnectionUseCase(repo)(input)
        assertEquals(listOf("http://exemple.com:8080"), repo.tested)
    }

    @Test fun `compte expire refuse`() = runTest {
        val repo = FakeRepo(mapOf("http://exemple.com:8080" to AppResult.Success(account(status = AccountStatus.EXPIRED))))
        val result = TestPortalConnectionUseCase(repo)(input)
        assertEquals(AppResult.Failure(AppError.AccountUnavailable("EXPIRED")), result)
    }
}
