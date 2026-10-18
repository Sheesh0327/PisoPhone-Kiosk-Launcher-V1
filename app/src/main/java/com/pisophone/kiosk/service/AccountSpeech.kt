package com.pisophone.kiosk.service

/**
 * What the phone says out loud when a player's QR card is accepted. The first scan of a card activates it ("Welcome, VIP card
 * activated"); every later scan greets the player by the name they chose ("Welcome, VIP Rowel"). The words are only chosen here; the
 * speaking, the owner's speech switch and quiet hours are the audio manager's.
 */
object AccountSpeech {
    const val CARD_ACTIVATED = "Welcome, VIP card activated"
    const val RETURNING_NO_NAME = "Welcome, VIP"

    /**
     * The words for an accepted scan. [firstScan] is true when the box gave the card its starter time (a card's first scan);
     * [name] is the player's display name, empty until they choose one.
     */
    fun welcome(firstScan: Boolean, name: String): String {
        if (firstScan) return CARD_ACTIVATED
        val spoken = spokenName(name)
        return if (spoken.isEmpty()) RETURNING_NO_NAME else "Welcome, VIP $spoken"
    }

    /** A name as a voice should read it: `_` and `-` are read as pauses, not as symbols, and extra spaces are dropped. */
    fun spokenName(name: String): String = name.replace('_', ' ').replace('-', ' ').trim().replace(Regex(" {2,}"), " ")
}
