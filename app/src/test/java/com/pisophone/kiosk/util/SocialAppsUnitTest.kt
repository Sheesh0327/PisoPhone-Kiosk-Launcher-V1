package com.pisophone.kiosk.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialAppsUnitTest {
    @Test
    fun `social apps, browsers and mail are on the list`() {
        assertTrue(SocialApps.isSocial("com.facebook.katana"))
        assertTrue(SocialApps.isSocial("com.facebook.lite"))
        assertTrue(SocialApps.isSocial("com.instagram.android"))
        assertTrue(SocialApps.isSocial("com.zhiliaoapp.musically"))
        assertTrue(SocialApps.isSocial("com.whatsapp"))
        assertTrue(SocialApps.isSocial("com.android.chrome"))
        assertTrue(SocialApps.isSocial("com.google.android.gm"))
    }

    @Test
    fun `games are never on the list`() {
        assertFalse(SocialApps.isSocial("com.mojang.minecraftpe"))
        assertFalse(SocialApps.isSocial("com.supercell.clashofclans"))
        assertFalse(SocialApps.isSocial("com.tencent.ig"))
        assertFalse(SocialApps.isSocial("com.garena.game.codm"))
    }

    @Test
    fun `the owner can add an app and take a built in one off`() {
        assertTrue(SocialApps.isSocial("com.example.chat", extra = setOf("com.example.chat")))
        assertFalse(SocialApps.isSocial("com.discord", excluded = setOf("com.discord")))
        assertFalse(SocialApps.isSocial("com.facebook.katana", excluded = setOf("com.facebook.katana")))
        // an app the owner added stays on the list even if it is also in the excluded set
        assertTrue(SocialApps.isSocial("com.discord", extra = setOf("com.discord"), excluded = setOf("com.discord")))
    }

    @Test
    fun `the packages to clear skip the kiosk itself`() {
        val installed = listOf("com.pisophone.kiosk", "com.facebook.katana", "com.mojang.minecraftpe", "com.android.chrome", "com.facebook.katana")
        assertEquals(
            listOf("com.facebook.katana", "com.android.chrome"),
            GuestReset.packagesToClear(installed, "com.pisophone.kiosk", emptySet(), emptySet()),
        )
    }
}
