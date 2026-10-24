package com.pisophone.kiosk.util

import android.content.Context
import android.content.SharedPreferences
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.BrandPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the shop's coin box told this phone about how it should look (feature 013): a palette id, the shop name, the header
 * title and how busy the animation is. A setting nobody chose is `null`, which means today's TechNet look.
 */
data class Branding(
    val palette: String? = null,
    val shopName: String? = null,
    val title: String? = null,
    val density: String? = null,
    /** When the box last sent these, in milliseconds since 1970; 0 when it never did. */
    val receivedAtMs: Long = 0L,
    /** The name of this phone's profile on the box (PisoTab, PisoPhone Pro, ...), or `null` when its slot has none (feature 024). */
    val deviceLabel: String? = null,
) {
    val isSet: Boolean get() = palette != null || shopName != null || title != null || density != null

    /** The title of the lock and home screens: this phone's label, else the shop's header title, else the default. */
    val displayTitle: String get() = deviceLabel ?: title ?: ShopBranding.DEFAULT_TITLE
}

/**
 * The shop's branding, kept in the device-protected preferences like [KioskSettings], so the lock screen can draw it before the
 * phone is unlocked and while the box is off. The phone never edits it: the only way in is [applyFromBox], the box's `/config`
 * push. The maker's credit ("by TechNet Solutions") is not here on purpose: nothing stored or pushed can address it.
 */
object ShopBranding {
    private const val PREFS = "shop_branding"

    private const val KEY_PALETTE = "palette"
    private const val KEY_NAME = "shop_name"
    private const val KEY_TITLE = "title"
    private const val KEY_DENSITY = "density"
    private const val KEY_LABEL = "device_label"
    private const val KEY_RECEIVED = "received_at"

    /** The header title of a phone nobody renamed. */
    const val DEFAULT_TITLE = "PisoPhone"

    const val MAX_NAME = 32
    const val MAX_TITLE = 20

    /** A device label is a profile name on the box: 1 to 20 characters. */
    const val MAX_LABEL = 20

    /** The animation density words, as the box's admin page offers them. `normal` is today's screen. */
    val DENSITY_WORDS: List<String> = listOf("off", "low", "normal", "high")

    private var live: MutableStateFlow<Branding>? = null

    /** The branding as it is now and whenever the box pushes new values, so a lock screen already up follows the box. */
    @Synchronized
    fun observe(context: Context): StateFlow<Branding> =
        live ?: MutableStateFlow(read(context)).also { live = it }

    private fun prefs(context: Context): SharedPreferences = KioskSecurity.getDirectBootPrefs(context.applicationContext, PREFS)

    /**
     * Owner-typed text made safe to draw: control characters, newlines and invisible direction marks removed, spaces trimmed,
     * cut to [max] characters, and a blank result is `null` (unset). Never used as a file name, a URL or a shell argument.
     */
    fun cleanText(raw: String?, max: Int): String? {
        if (raw == null) return null
        val kept = StringBuilder()
        raw.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            val drop = type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                type == Character.UNASSIGNED.toInt() ||
                type == Character.SURROGATE.toInt()
            if (!drop) kept.appendCodePoint(cp)
        }
        val trimmed = kept.toString().trim()
        val cutAt = if (trimmed.codePointCount(0, trimmed.length) > max) trimmed.offsetByCodePoints(0, max) else trimmed.length
        return trimmed.substring(0, cutAt).trim().ifEmpty { null }
    }

    /** The id if it is one of the built-in palettes, else `null`. An unknown id never reaches storage. */
    fun cleanPalette(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { id -> BrandPalette.all.any { it.id == id } }

    /** The density word if it is one of [DENSITY_WORDS], else `null`. */
    fun cleanDensity(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it in DENSITY_WORDS }

    /** What is stored now; the defaults (all unset) when the box never sent anything. */
    fun read(context: Context): Branding {
        val p = prefs(context)
        return Branding(
            palette = cleanPalette(p.getString(KEY_PALETTE, null)),
            shopName = cleanText(p.getString(KEY_NAME, null), MAX_NAME),
            title = cleanText(p.getString(KEY_TITLE, null), MAX_TITLE),
            density = cleanDensity(p.getString(KEY_DENSITY, null)),
            receivedAtMs = p.getLong(KEY_RECEIVED, 0L),
            deviceLabel = cleanText(p.getString(KEY_LABEL, null), MAX_LABEL),
        )
    }

    /**
     * Stores what the box pushed. A parameter the box did not send (`null`) changes nothing, so an old box leaves the branding
     * alone. A name or title that is sent but blank clears that setting; a palette or density sent blank goes back to the
     * default; a palette id or density word nobody knows is ignored and the stored one stays. Returns true if anything was
     * sent, which is also when the "last heard from the box" time moves.
     */
    fun applyFromBox(
        context: Context,
        palette: String?,
        shopName: String?,
        title: String?,
        density: String?,
        nowMs: Long = System.currentTimeMillis(),
        deviceLabel: String? = null,
    ): Boolean {
        if (listOf(palette, shopName, title, density, deviceLabel).all { it == null }) return false
        val edit = prefs(context).edit()
        if (palette != null) {
            if (palette.isBlank()) {
                edit.remove(KEY_PALETTE)
            } else {
                cleanPalette(palette)?.let { edit.putString(KEY_PALETTE, it) }
            }
        }
        if (shopName != null) putText(edit, KEY_NAME, cleanText(shopName, MAX_NAME))
        if (title != null) putText(edit, KEY_TITLE, cleanText(title, MAX_TITLE))
        // a blank label (the box sends one when the slot went back to the standard rate) clears it
        if (deviceLabel != null) putText(edit, KEY_LABEL, cleanText(deviceLabel, MAX_LABEL))
        if (density != null) {
            if (density.isBlank()) {
                edit.remove(KEY_DENSITY)
            } else {
                cleanDensity(density)?.let { edit.putString(KEY_DENSITY, it) }
            }
        }
        edit.putLong(KEY_RECEIVED, nowMs).apply()
        publish(context)
        return true
    }

    @Synchronized
    private fun publish(context: Context) {
        live?.value = read(context)
    }

    private fun putText(edit: SharedPreferences.Editor, key: String, value: String?) {
        if (value == null) edit.remove(key) else edit.putString(key, value)
    }
}
