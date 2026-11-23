package dev.mytv.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des fichiers de sauvegarde par phrase secrète (le fichier quitte la TV et contient
 * les mots de passe des portails) :
 * - clé AES-256 dérivée par PBKDF2 (210 000 itérations, sel aléatoire de 16 octets) : chaque essai
 *   de phrase coûte cher, ce qui freine une attaque par force brute ;
 * - chiffrement AES-GCM : toute modification du fichier est détectée (échec du déchiffrement).
 *
 * Indépendant d'Android (java.util.Base64, javax.crypto) : testable en JVM pur.
 */
object BackupCrypto {

    @Serializable
    data class Envelope(
        // Pas de valeur par défaut à la lecture : un JSON sans "format" n'est pas une sauvegarde MyTV.
        val format: String,
        val version: Int = 1,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    class WrongPassphraseOrCorruptedException(cause: Throwable? = null) : Exception("Sauvegarde illisible", cause)

    private const val FORMAT = "mytv-backup"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    // encodeDefaults : le marqueur "format" et la version doivent figurer dans le fichier, même
    // s'ils ont leur valeur par défaut (sinon on ne reconnaît pas une sauvegarde MyTV).
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val kdf = bestKdf()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, kdf, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val envelope = Envelope(
            format = FORMAT,
            kdf = kdf,
            iterations = ITERATIONS,
            salt = b64(salt),
            iv = b64(iv),
            data = b64(cipher.doFinal(plain)),
        )
        return json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /** @throws WrongPassphraseOrCorruptedException mauvaise phrase, fichier modifié ou pas une sauvegarde MyTV. */
    fun decrypt(file: ByteArray, passphrase: String): ByteArray {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), file.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw WrongPassphraseOrCorruptedException(e)
        }
        if (envelope.format != FORMAT) throw WrongPassphraseOrCorruptedException()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = deriveKey(passphrase, unb64(envelope.salt), envelope.kdf, envelope.iterations)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, unb64(envelope.iv)))
            cipher.doFinal(unb64(envelope.data))
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseOrCorruptedException(e)
        } catch (e: IllegalArgumentException) {
            throw WrongPassphraseOrCorruptedException(e)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, kdf: String, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** PBKDF2-SHA256 n'existe qu'à partir d'Android 8 (API 26) ; repli SHA-1 sur Android 7.1. */
    private fun bestKdf(): String = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        "PBKDF2WithHmacSHA256"
    } catch (_: NoSuchAlgorithmException) {
        "PBKDF2WithHmacSHA1"
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)
}
