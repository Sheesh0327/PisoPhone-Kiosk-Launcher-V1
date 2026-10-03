package com.pisophone.kiosk.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CoinSpeechUnitTest {
    @Test
    fun `five pesos for five minutes`() {
        assertEquals("5 pesos added. 5 minutes.", CoinSpeech.confirmation(5, 300))
    }

    @Test
    fun `singular wording`() {
        assertEquals("1 peso added. 1 minute.", CoinSpeech.confirmation(1, 60))
    }

    @Test
    fun `seconds are spoken when not whole minutes`() {
        assertEquals("1 peso added. 2 minutes 30 seconds.", CoinSpeech.confirmation(1, 150))
        assertEquals("1 peso added. 45 seconds.", CoinSpeech.confirmation(1, 45))
    }
}
