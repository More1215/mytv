package dev.mytv.data.source.xtream

import dev.mytv.data.local.db.searchKey
import dev.mytv.data.remote.xtream.dto.parseDurationSec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Réponses VOD réalistes, avec les incohérences habituelles des panels Xtream. */
class XtreamVodParserTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test fun `fiche film complete`() {
        val details = XtreamVodParser.movieDetails(
            42,
            json(
                """
                {"info":{"movie_image":"http://x/p.jpg","plot":"Un résumé","genre":"Action","cast":"A, B",
                  "releasedate":"2021-05-01","duration_secs":"6130","rating":"7,4","backdrop_path":["http://x/b.jpg"]},
                 "movie_data":{"stream_id":42,"name":"Le Film","container_extension":"MKV","category_id":"3"}}
                """,
            ),
        )
        assertEquals("Le Film", details.movie.name)
        assertEquals("mkv", details.movie.containerExtension)
        assertEquals(7.4, details.movie.rating!!, 0.001)
        assertEquals(6130L, details.durationSec)
        assertEquals("http://x/b.jpg", details.backdropUrl) // image fournie en tableau
    }

    @Test fun `fiche film vide - info en tableau et duree texte`() {
        val details = XtreamVodParser.movieDetails(
            7,
            json("""{"info":[],"movie_data":{"name":"X","container_extension":null}}"""),
        )
        assertEquals("X", details.movie.name)
        assertEquals("mp4", details.movie.containerExtension) // extension par défaut
        assertNull(details.plot)
    }

    @Test fun `serie avec episodes indexes par saison et non tries`() {
        val details = XtreamVodParser.seriesDetails(
            9,
            json(
                """
                {"info":{"name":"Ma Série","cover":"http://x/c.jpg"},
                 "seasons":[{"season_number":2,"name":"Saison deux"}],
                 "episodes":{
                   "2":[{"id":"21","episode_num":1,"title":"S2E1","season":2,"container_extension":"mp4","info":{"duration":"00:45:00"}}],
                   "1":[{"id":"12","episode_num":2,"title":"S1E2","season":1,"container_extension":"mkv","info":[]},
                        {"id":"11","episode_num":"1","title":"S1E1","season":"1","container_extension":"mkv","info":{}}]
                 }}
                """,
            ),
        )
        assertEquals(listOf(1, 2), details.seasons.map { it.number })
        assertEquals(listOf(11L, 12L), details.seasons[0].episodes.map { it.id })
        assertEquals("Saison 1", details.seasons[0].name)
        assertEquals("Saison deux", details.seasons[1].name)
        assertEquals(2700L, details.seasons[1].episodes[0].durationSec)
    }

    @Test fun `serie avec episodes en tableau plat`() {
        val details = XtreamVodParser.seriesDetails(
            9,
            json("""{"info":{"name":"S"},"episodes":[{"id":5,"episode_num":1,"season":1,"title":"Pilote"}]}"""),
        )
        assertEquals(1, details.seasons.size)
        assertEquals("Pilote", details.seasons[0].episodes[0].title)
    }

    @Test fun `durees et recherche normalisee`() {
        assertEquals(6130L, parseDurationSec("01:42:10"))
        assertEquals(2700L, parseDurationSec("45:00"))
        assertNull(parseDurationSec(""))
        assertEquals("ete brulant", searchKey("  Été   BRÛLANT "))
    }
}
