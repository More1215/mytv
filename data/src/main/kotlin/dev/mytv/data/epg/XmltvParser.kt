package dev.mytv.data.epg

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Programme extrait du XMLTV. */
data class XmltvProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Parseur XMLTV en flux (pull parser) : le fichier, qui peut peser plus de 100 Mo, n'est jamais
 * chargé en mémoire. Seuls les programmes des chaînes connues ([wantedChannels]) et chevauchant
 * la fenêtre [fromMs]..[toMs] sont conservés, puis transmis par lots à [onBatch]
 * (insertion Room par paquets, mémoire constante).
 *
 * @return nombre de programmes retenus.
 */
class XmltvParser(
    private val wantedChannels: Set<String>,
    private val fromMs: Long,
    private val toMs: Long,
    private val batchSize: Int = 1000,
    /** Injectable pour les tests JVM (kxml2) ; sur Android, le parseur natif du système. */
    private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
) {
    suspend fun parse(input: InputStream, onBatch: suspend (List<XmltvProgram>) -> Unit): Int {
        val parser = newParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val batch = ArrayList<XmltvProgram>(batchSize)
        var kept = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                readProgramme(parser)?.let {
                    batch += it
                    kept++
                    if (batch.size >= batchSize) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
            }
            event = parser.next()
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        return kept
    }

    /** Lit un <programme> ; renvoie null s'il est hors fenêtre ou pour une chaîne inconnue. */
    private fun readProgramme(parser: XmlPullParser): XmltvProgram? {
        val channel = parser.getAttributeValue(null, "channel")?.trim()
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
        val relevant = channel != null && channel in wantedChannels &&
            start != null && stop != null && stop > fromMs && start < toMs

        var title: String? = null
        var desc: String? = null
        val depth = parser.depth
        // On consomme toujours le sous-arbre, même si le programme est ignoré.
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (!relevant || parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> if (title == null) title = parser.nextText().trim()
                "desc" -> if (desc == null) desc = parser.nextText().trim()
            }
        }
        if (!relevant || title.isNullOrEmpty()) return null
        return XmltvProgram(channel!!, title, desc?.ifEmpty { null }, start!!, stop!!)
    }

    companion object {
        // SimpleDateFormat n'est pas thread-safe : une instance par thread.
        private val format = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        }
        private val formatNoZone = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        /** Format XMLTV : "20250101203000 +0100" (le fuseau est parfois absent : UTC supposé). */
        fun parseXmltvTime(raw: String?): Long? {
            val value = raw?.trim() ?: return null
            if (value.length < 14) return null
            val withZone = value.contains(' ') || value.contains('+') || value.lastIndexOf('-') > 0
            return if (withZone) {
                val normalized = value.replace(Regex("^(\\d{14})\\s*([+-]\\d{4}).*$"), "$1 $2")
                format.get()!!.parse(normalized, ParsePosition(0))?.time
            } else {
                formatNoZone.get()!!.parse(value.take(14), ParsePosition(0))?.time
            }
        }
    }
}
