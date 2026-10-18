package com.pisophone.kiosk.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSpeechTest {
    @Test
    fun theFirstScanActivatesTheCardWhateverTheName() {
        assertEquals("Welcome, VIP card activated", AccountSpeech.welcome(firstScan = true, name = ""))
        assertEquals("Welcome, VIP card activated", AccountSpeech.welcome(firstScan = true, name = "Rowel"))
    }

    @Test
    fun laterScansGreetThePlayerByName() {
        assertEquals("Welcome, VIP Rowel", AccountSpeech.welcome(firstScan = false, name = "Rowel"))
        assertEquals("Welcome, VIP Mark Anthony", AccountSpeech.welcome(firstScan = false, name = "Mark Anthony"))
    }

    @Test
    fun aPlayerWhoNeverChoseANameIsStillWelcomed() {
        assertEquals("Welcome, VIP", AccountSpeech.welcome(firstScan = false, name = ""))
        assertEquals("Welcome, VIP", AccountSpeech.welcome(firstScan = false, name = "  _-  "))
    }

    @Test
    fun symbolsInANameAreReadAsPauses() {
        assertEquals("Welcome, VIP Kid One", AccountSpeech.welcome(firstScan = false, name = "Kid_One"))
        assertEquals("Welcome, VIP Ace of Spades", AccountSpeech.welcome(firstScan = false, name = "Ace-of_Spades"))
        assertEquals("Ace 7", AccountSpeech.spokenName("Ace - 7"))
    }
}
