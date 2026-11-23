package dev.mytv.data.epg

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class XmltvParserTest {
    private val hour = 3_600_000L
    // 2025-01-01 12:00:00 UTC
    private val noon = 1735732800000L

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="a.fr"><display-name>A</display-name></channel>
          <programme start="20250101110000 +0000" stop="20250101120000 +0000" channel="a.fr">
            <title lang="fr">Passé proche</title>
          </programme>
          <programme start="20250101130000 +0100" stop="20250101140000 +0100" channel="a.fr">
            <title lang="fr">En cours</title><desc lang="fr">Résumé &amp; détails</desc>
            <credits><actor>X</actor></credits>
          </programme>
          <programme start="20250102120000 +0000" stop="20250102130000 +0000" channel="a.fr">
            <title>Trop loin</title>
          </programme>
          <programme start="20250101120000 +0000" stop="20250101130000 +0000" channel="inconnue">
            <title>Chaîne absente du catalogue</title>
          </programme>
          <programme start="20250101123000 +0000" stop="20250101133000 +0000" channel="a.fr">
            <desc>Sans titre : ignoré</desc>
          </programme>
        </tv>
    """.trimIndent()

    @Test fun `filtre par chaine et par fenetre et lit titre et description`() = runTest {
        val parser = XmltvParser(setOf("a.fr"), fromMs = noon - 30 * 60_000, toMs = noon + 6 * hour, batchSize = 1) { KXmlParser() }
        val out = mutableListOf<XmltvProgram>()
        val kept = parser.parse(xml.byteInputStream()) { out += it }

        assertEquals(2, kept)
        assertEquals(listOf("Passé proche", "En cours"), out.map { it.title })
        // "13:00 +0100" = 12:00 UTC : le fuseau est bien pris en compte.
        assertEquals(noon, out[1].startMs)
        assertEquals("Résumé & détails", out[1].description)
        assertNull(out[0].description)
    }

    @Test fun `heure sans fuseau interpretee en UTC`() {
        assertEquals(noon, XmltvParser.parseXmltvTime("20250101120000"))
        assertEquals(noon, XmltvParser.parseXmltvTime("20250101140000 +0200"))
        assertEquals(noon, XmltvParser.parseXmltvTime("20250101070000 -0500"))
        assertNull(XmltvParser.parseXmltvTime("2025"))
    }
}
