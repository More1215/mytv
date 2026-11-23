package dev.mytv.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test
    fun `comparaison numerique champ par champ`() {
        assertTrue(VersionCompare.isNewer("1.0.4", "1.0.3"))
        assertTrue(VersionCompare.isNewer("v1.0.10", "1.0.9"))
        assertTrue(VersionCompare.isNewer("1.1", "1.0.9"))
        assertFalse(VersionCompare.isNewer("1.0.3", "1.0.3"))
        assertFalse(VersionCompare.isNewer("1.0.2", "1.0.3"))
        assertFalse(VersionCompare.isNewer("1.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.3", "1.0.3-debug"))
    }
}
