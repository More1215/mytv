package dev.mytv.data.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val pass = "phrase secrète 42"
    private val secretPassword = "MotDePassePortail!"
    private val payload = BackupPayload(
        createdAtMs = 1,
        portals = listOf(BackupPortal("P", "XTREAM", "http://h:8080", "u", secretPassword, 1)),
    )
    private val plain = Json.encodeToString(BackupPayload.serializer(), payload).toByteArray()

    @Test fun `aller-retour avec la bonne phrase`() {
        val file = BackupCrypto.encrypt(plain, pass)
        val back = Json.decodeFromString(BackupPayload.serializer(), BackupCrypto.decrypt(file, pass).toString(Charsets.UTF_8))
        assertEquals(payload, back)
    }

    @Test fun `le mot de passe du portail n'apparait pas en clair dans le fichier`() {
        val file = BackupCrypto.encrypt(plain, pass).toString(Charsets.UTF_8)
        assertFalse(file.contains(secretPassword))
        assertFalse(file.contains("http://h:8080"))
        assertTrue(file.contains("mytv-backup"))
    }

    @Test(expected = BackupCrypto.WrongPassphraseOrCorruptedException::class)
    fun `mauvaise phrase refusee`() {
        BackupCrypto.decrypt(BackupCrypto.encrypt(plain, pass), "mauvaise phrase")
    }

    @Test(expected = BackupCrypto.WrongPassphraseOrCorruptedException::class)
    fun `fichier modifie detecte`() {
        val file = BackupCrypto.encrypt(plain, pass).toString(Charsets.UTF_8)
        // On altère un caractère des données chiffrées : GCM doit refuser.
        val tampered = file.replace(Regex("(\"data\"\\s*:\\s*\")(.)")) { m ->
            m.groupValues[1] + (if (m.groupValues[2] == "A") "B" else "A")
        }
        BackupCrypto.decrypt(tampered.toByteArray(), pass)
    }

    @Test(expected = BackupCrypto.WrongPassphraseOrCorruptedException::class)
    fun `fichier quelconque refuse`() {
        BackupCrypto.decrypt("{\"hello\":1}".toByteArray(), pass)
    }

    @Test fun `deux sauvegardes du meme contenu sont differentes (sel et iv aleatoires)`() {
        assertFalse(BackupCrypto.encrypt(plain, pass).contentEquals(BackupCrypto.encrypt(plain, pass)))
    }
}
