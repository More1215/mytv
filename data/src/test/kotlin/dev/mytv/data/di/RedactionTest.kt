package dev.mytv.data.di

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactionTest {
    @Test fun `masque les identifiants dans les parametres`() {
        assertEquals(
            "--> GET http://h:80/player_api.php?username=***&password=***&action=get_live_streams",
            "--> GET http://h:80/player_api.php?username=jean&password=s3cr3t&action=get_live_streams".redactCredentials(),
        )
    }

    @Test fun `masque les identifiants dans le chemin d'un flux`() {
        assertEquals(
            "--> GET http://h:80/live/***/***/1234.ts",
            "--> GET http://h:80/live/jean/s3cr3t/1234.ts".redactCredentials(),
        )
    }
}
