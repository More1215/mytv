package dev.mytv.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test fun `ajoute http si le schema manque`() =
        assertEquals("http://example.com:8080", ServerUrl.normalize("example.com:8080"))

    @Test fun `conserve https`() =
        assertEquals("https://example.com", ServerUrl.normalize("https://example.com/"))

    @Test fun `retire player_api et la query string`() =
        assertEquals(
            "http://example.com:8080",
            ServerUrl.normalize("http://example.com:8080/player_api.php?username=a&password=b"),
        )

    @Test fun `retire get php d une url m3u`() =
        assertEquals("http://example.com", ServerUrl.normalize(" http://example.com/get.php?type=m3u "))

    @Test fun `conserve un sous chemin`() =
        assertEquals("http://example.com/panel", ServerUrl.normalize("example.com/panel/"))

    @Test fun `candidat https sur le port annonce`() {
        assertEquals("https://exemple.com:8443/panel", ServerUrl.httpsCandidate("http://exemple.com:8080/panel", 8443))
        assertEquals("https://exemple.com", ServerUrl.httpsCandidate("http://exemple.com", 443))
        assertNull(ServerUrl.httpsCandidate("https://exemple.com", 443))
        assertNull(ServerUrl.httpsCandidate("http://exemple.com", null))
        assertNull(ServerUrl.httpsCandidate("http://exemple.com", 0))
    }

    @Test fun `refuse une saisie vide ou sans hote`() {
        assertNull(ServerUrl.normalize("   "))
        assertNull(ServerUrl.normalize("http://"))
        assertNull(ServerUrl.normalize("abc"))
    }
}
