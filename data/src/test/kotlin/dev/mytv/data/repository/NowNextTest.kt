package dev.mytv.data.repository

import dev.mytv.domain.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowNextTest {
    private fun p(title: String, start: Long, end: Long) = EpgProgram(title, null, start, end)

    @Test fun `programme en cours et suivant`() {
        val result = listOf(p("C", 200, 300), p("A", 0, 100), p("B", 100, 200)).toNowNext(150)
        assertEquals("B", result.now?.title)
        assertEquals("C", result.next?.title)
    }

    @Test fun `trou dans la grille - pas de programme en cours mais un suivant`() {
        val result = listOf(p("A", 0, 100), p("B", 200, 300)).toNowNext(150)
        assertNull(result.now)
        assertEquals("B", result.next?.title)
    }

    @Test fun `liste vide`() {
        val result = emptyList<EpgProgram>().toNowNext(0)
        assertNull(result.now)
        assertNull(result.next)
    }
}
