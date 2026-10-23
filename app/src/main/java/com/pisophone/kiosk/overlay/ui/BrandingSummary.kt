package com.pisophone.kiosk.overlay.ui

import android.content.Context
import com.pisophone.kiosk.R
import com.pisophone.kiosk.util.Branding

/**
 * The rows of the owner vault's branding card (feature 012): what the coin box told this phone, and when. The phone never edits
 * these (the box's admin page is the one place they are set), so this only reads; a row that was never sent shows its default.
 */
object BrandingSummary {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** Label and value, in the order the card shows them. */
    fun rows(context: Context, b: Branding, nowMs: Long): List<Pair<String, String>> = listOf(
        context.getString(R.string.vault_branding_palette) to
            (b.palette?.let { if (it == "technet") "TechNet" else it.replaceFirstChar { c -> c.uppercase() } } ?: context.getString(R.string.vault_branding_default_palette)),
        context.getString(R.string.vault_branding_name) to (b.shopName ?: context.getString(R.string.vault_branding_not_set)),
        context.getString(R.string.vault_branding_headline) to (b.title ?: context.getString(R.string.vault_branding_default_title)),
        context.getString(R.string.vault_branding_density) to
            (b.density?.replaceFirstChar { it.uppercase() } ?: context.getString(R.string.vault_branding_default_density)),
        context.getString(R.string.vault_branding_heard) to heard(context, b.receivedAtMs, nowMs),
    )

    private fun heard(context: Context, receivedAtMs: Long, nowMs: Long): String {
        if (receivedAtMs <= 0L) return context.getString(R.string.vault_branding_never)
        val ago = (nowMs - receivedAtMs).coerceAtLeast(0L) // a clock that went backwards reads as "just now"
        return when {
            ago < MINUTE_MS -> context.getString(R.string.vault_branding_just_now)
            ago < HOUR_MS -> context.getString(R.string.vault_branding_min_ago, (ago / MINUTE_MS).toInt())
            ago < DAY_MS -> context.getString(R.string.vault_branding_hours_ago, (ago / HOUR_MS).toInt())
            else -> context.getString(R.string.vault_branding_days_ago, (ago / DAY_MS).toInt())
        }
    }
}
