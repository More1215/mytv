package dev.mytv.domain.util

import dev.mytv.domain.model.PortalCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalLinkParserTest {

    @Test
    fun `lien get php du fournisseur`() {
        assertEquals(
            PortalCredentials("http://srv.example.com:8080", "jean", "s3cr3t"),
            PortalLinkParser.parse("http://srv.example.com:8080/get.php?username=jean&password=s3cr3t&type=m3u_plus&output=ts"),
        )
    }

    @Test
    fun `lien au milieu d'un texte et caracteres encodes`() {
        assertEquals(
            PortalCredentials("https://srv.example.com", "jean@mail", "a b&c"),
            PortalLinkParser.parse("Voici ton lien : https://srv.example.com/player_api.php?username=jean%40mail&password=a%20b%26c."),
        )
    }

    @Test
    fun `lien de flux live et forme courte`() {
        val expected = PortalCredentials("http://srv.example.com:80", "u1", "p1")
        assertEquals(expected, PortalLinkParser.parse("http://srv.example.com:80/live/u1/p1/1234.ts"))
        assertEquals(expected, PortalLinkParser.parse("http://srv.example.com:80/u1/p1/1234"))
    }

    @Test
    fun `texte du fournisseur, valeurs sur la meme ligne`() {
        val text = """
            Domain: fairy.example.cyou
            Username: test
            Password: motdepasse42
        """.trimIndent()
        assertEquals(PortalCredentials("fairy.example.cyou", "test", "motdepasse42"), PortalLinkParser.parse(text))
    }

    @Test
    fun `tableau copie, valeur sur la ligne suivante, libelles francais et port separe`() {
        val text = "URL du serveur\nmonserveur.tv\nNom d'utilisateur :\nmarie\nMot de passe\nxyz\nPort: 8080"
        assertEquals(PortalCredentials("monserveur.tv:8080", "marie", "xyz"), PortalLinkParser.parse(text))
    }

    @Test
    fun `lien sans identifiants complete par le texte`() {
        val text = "Serveur : http://srv.example.com:8080\nUser: a\nPass: b"
        assertEquals(PortalCredentials("http://srv.example.com:8080", "a", "b"), PortalLinkParser.parse(text))
    }

    @Test
    fun `rien d'exploitable`() {
        assertNull(PortalLinkParser.parse("bonjour"))
        assertNull(PortalLinkParser.parse("http://srv.example.com:8080/"))
        assertNull(PortalLinkParser.parse("Username: a\nPassword: b"))
    }
}
