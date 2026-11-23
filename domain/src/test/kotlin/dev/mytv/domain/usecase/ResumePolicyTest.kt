package dev.mytv.domain.usecase

import dev.mytv.domain.model.Episode
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.model.Season
import dev.mytv.domain.model.Series
import dev.mytv.domain.model.SeriesDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private fun point(pos: Long, dur: Long) = ResumePoint("movie:1", pos, dur, "t", null, null, null, 0)

    @Test fun `reprise au milieu`() = assertEquals(600_000L, ResumePolicy.startPosition(point(600_000, 6_000_000)))

    @Test fun `trop tot pour reprendre`() = assertEquals(0L, ResumePolicy.startPosition(point(10_000, 6_000_000)))

    @Test fun `deja vu - on repart du debut`() = assertEquals(0L, ResumePolicy.startPosition(point(5_800_000, 6_000_000)))

    @Test fun `aucune position`() = assertEquals(0L, ResumePolicy.startPosition(null))

    @Test fun `seuil vu a 95 pourcent`() {
        assertTrue(ResumePolicy.isWatched(950, 1000))
        assertFalse(ResumePolicy.isWatched(940, 1000))
        assertFalse(ResumePolicy.isWatched(940, 0)) // durée inconnue (flux sans durée)
    }

    @Test fun `episode suivant y compris changement de saison`() {
        fun ep(id: Long, s: Int, n: Int) = Episode(id, 9, s, n, "E$n", null, null, null, "mp4")
        val details = SeriesDetails(
            Series(9, "S", null, null, null), null, null, null, null, null,
            listOf(Season(1, "Saison 1", listOf(ep(1, 1, 1), ep(2, 1, 2))), Season(2, "Saison 2", listOf(ep(3, 2, 1)))),
        )
        assertEquals(2L, details.nextEpisodeAfter(1)?.id)
        assertEquals(3L, details.nextEpisodeAfter(2)?.id)
        assertNull(details.nextEpisodeAfter(3))
    }
}
