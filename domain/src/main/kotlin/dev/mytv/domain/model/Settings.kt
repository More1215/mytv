package dev.mytv.domain.model

/** Format demandé au portail pour les chaînes en direct. */
enum class LiveFormat(val extension: String, val label: String) {
    /** MPEG-TS direct : démarrage le plus rapide, recommandé. */
    TS("ts", "MPEG-TS (rapide)"),
    /** HLS : plus robuste sur les connexions instables, démarrage un peu plus lent. */
    HLS("m3u8", "HLS (robuste)"),
}

/**
 * Taille de la mémoire tampon du lecteur. Valeurs pensées pour le Fire TV Stick (2 Go de RAM) :
 * même « Stabilité » reste raisonnable en mémoire.
 */
enum class BufferProfile(
    val label: String,
    val startMs: Int,
    val minMs: Int,
    val maxMs: Int,
    val afterRebufferMs: Int,
) {
    FAST("Démarrage rapide", startMs = 1_000, minMs = 15_000, maxMs = 30_000, afterRebufferMs = 3_000),
    BALANCED("Équilibré", startMs = 2_500, minMs = 25_000, maxMs = 50_000, afterRebufferMs = 5_000),
    STABLE("Stabilité (connexion lente)", startMs = 5_000, minMs = 40_000, maxMs = 80_000, afterRebufferMs = 10_000),
}

enum class DecoderMode(val label: String) {
    /** Décodeurs matériels de l'appareil : économes, recommandés. */
    HARDWARE("Matériel (recommandé)"),
    /** Décodeurs logiciels Android en priorité : utile si une chaîne s'affiche mal (image verte, saccades). */
    SOFTWARE("Logiciel (en cas de problème d'image)"),
}

data class PlayerSettings(
    val liveFormat: LiveFormat = LiveFormat.TS,
    val buffer: BufferProfile = BufferProfile.FAST,
    val decoder: DecoderMode = DecoderMode.HARDWARE,
    /** Relancer automatiquement la dernière chaîne au démarrage de l'appli. */
    val resumeLastChannel: Boolean = true,
)
