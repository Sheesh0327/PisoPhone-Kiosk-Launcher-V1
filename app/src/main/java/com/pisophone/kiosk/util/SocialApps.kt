package com.pisophone.kiosk.util

/**
 * The apps whose data is wiped when a phone has been without paid time for a while: social networks, chat, mail, video
 * accounts and every browser (logins and history are the biggest privacy risk on a shared phone). Games are never on this list:
 * their downloaded data is what makes them playable. The owner can add apps and take built-in ones off (owner settings).
 */
object SocialApps {
    /** Exact package names. */
    val DEFAULT_PACKAGES: Set<String> = setOf(
        // social networks
        "com.twitter.android",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.pinterest",
        "com.linkedin.android",
        "com.tumblr",
        "com.bumble.app",
        "com.tinder",
        "com.discord",
        "tv.twitch.android.app",
        // chat
        "com.viber.voip",
        "com.skype.raider",
        "jp.naver.line.android",
        "com.kakao.talk",
        "com.imo.android.imoim",
        "com.google.android.apps.messaging",
        "com.google.android.apps.tachyon",
        // mail and video accounts
        "com.google.android.gm",
        "com.google.android.apps.gmail",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail",
        // browsers
        "com.android.chrome",
        "com.chrome.beta",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.UCMobile.intl",
        "com.vivo.browser",
        "com.heytap.browser",
        "com.duckduckgo.mobile.android",
        "com.android.browser",
    )

    /** Package-name prefixes: the Facebook, Instagram, TikTok, WhatsApp and Telegram families have many package names. */
    val DEFAULT_PREFIXES: List<String> = listOf(
        "com.facebook.",
        "com.instagram.",
        "com.whatsapp",
        "org.telegram.",
        "com.zhiliaoapp.",
        "com.ss.android.ugc.",
        "org.thunderdog.challegram",
    )

    /** True when [packageName] is on the wipe list: built in (and not taken off by the owner) or added by the owner. */
    fun isSocial(packageName: String, extra: Set<String> = emptySet(), excluded: Set<String> = emptySet()): Boolean {
        if (packageName in extra) return true
        if (packageName in excluded) return false
        return packageName in DEFAULT_PACKAGES || DEFAULT_PREFIXES.any { packageName.startsWith(it) }
    }
}
