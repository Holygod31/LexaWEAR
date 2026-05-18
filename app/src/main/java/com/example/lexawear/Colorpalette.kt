package com.example.lexawear

import kotlin.math.sqrt

/**
 * Single source of truth for LexaWEAR's curated colour palette.
 *
 * Each entry holds:
 *   - [hex]      — the canonical 6-char uppercase hex stored on NFC tags
 *   - [nameRes]  — string resource ID for the localised display name
 *   - [r],[g],[b]— pre-computed RGB components for nearest-match calculations
 *
 * Usage:
 *   // Hex → localised name (exact match)
 *   val name = ColorPalette.nameForHex(hex, ::getString)
 *
 *   // RGB → nearest palette entry (for computer vision colour detection)
 *   val entry = ColorPalette.nearestEntry(r, g, b)
 *   val hex   = entry.hex
 *   val name  = getString(entry.nameRes)
 */
object ColorPalette {

    data class Entry(
        val hex: String,
        val nameRes: Int,
        val r: Int,
        val g: Int,
        val b: Int
    )

    val entries: List<Entry> = listOf(

        // ── Reds ──────────────────────────────────────────────────────────────
        Entry("B71C1C", R.string.color_dark_red,      183,  28,  28),
        Entry("F44336", R.string.color_red,            244,  67,  54),
        Entry("E53935", R.string.color_crimson,        229,  57,  53),
        Entry("880E4F", R.string.color_burgundy,       136,  14,  79),
        Entry("FF7043", R.string.color_coral,          255, 112,  67),
        Entry("FF8A65", R.string.color_salmon,         255, 138, 101),

        // ── Pinks ────────────────────────────────────────────────────────────
        Entry("E91E63", R.string.color_pink,           233,  30,  99),
        Entry("F06292", R.string.color_light_pink,     240,  98, 146),
        Entry("FF4081", R.string.color_hot_pink,       255,  64, 129),
        Entry("AD1457", R.string.color_deep_pink,      173,  20,  87),
        Entry("CE93D8", R.string.color_mauve,          206, 147, 216),

        // ── Oranges ──────────────────────────────────────────────────────────
        Entry("FF9800", R.string.color_orange,         255, 152,   0),
        Entry("FFCC80", R.string.color_peach,          255, 204, 128),
        Entry("BF360C", R.string.color_rust,           191,  54,  12),
        Entry("A1887F", R.string.color_terracotta,     161, 136, 127),

        // ── Yellows ──────────────────────────────────────────────────────────
        Entry("FFEB3B", R.string.color_yellow,         255, 235,  59),
        Entry("FFC107", R.string.color_mustard,        255, 193,   7),
        Entry("FFD700", R.string.color_gold,           255, 215,   0),
        Entry("FFF8E1", R.string.color_cream,          255, 248, 225),

        // ── Greens ───────────────────────────────────────────────────────────
        Entry("4CAF50", R.string.color_green,           76, 175,  80),
        Entry("CDDC39", R.string.color_lime,           205, 220,  57),
        Entry("827717", R.string.color_olive,          130, 119,  23),
        Entry("1B5E20", R.string.color_forest_green,    27,  94,  32),
        Entry("80CBC4", R.string.color_mint,           128, 203, 196),
        Entry("009688", R.string.color_teal,             0, 150, 136),

        // ── Blues ────────────────────────────────────────────────────────────
        Entry("81D4FA", R.string.color_sky_blue,       129, 212, 250),
        Entry("2196F3", R.string.color_blue,            33, 150, 243),
        Entry("1565C0", R.string.color_royal_blue,      21, 101, 192),
        Entry("1A237E", R.string.color_navy,            26,  35, 126),
        Entry("1E88E5", R.string.color_cobalt,          30, 136, 229),
        Entry("00BCD4", R.string.color_turquoise,        0, 188, 212),

        // ── Purples ──────────────────────────────────────────────────────────
        Entry("9C27B0", R.string.color_purple,         156,  39, 176),
        Entry("E8EAF6", R.string.color_lavender,       232, 234, 246),
        Entry("7B1FA2", R.string.color_violet,         123,  31, 162),
        Entry("4A148C", R.string.color_plum,            74,  20, 140),

        // ── Neutrals ────────────────────────────────────────────────────────
        Entry("F5F5F5", R.string.color_white,          245, 245, 245),
        Entry("FFF8DC", R.string.color_off_white,      255, 248, 220),
        Entry("D7CCC8", R.string.color_beige,          215, 204, 200),
        Entry("D2B48C", R.string.color_tan,            210, 180, 140),
        Entry("795548", R.string.color_brown,          121,  85,  72),
        Entry("9E9E9E", R.string.color_grey,           158, 158, 158),
        Entry("424242", R.string.color_charcoal,        66,  66,  66),
        Entry("212121", R.string.color_black,           33,  33,  33),

        // ── Special ──────────────────────────────────────────────────────────
        Entry("FF5722", R.string.color_multicolor,     255,  87,  34)
    )

    /**
     * Returns the localised display name for an exact hex match,
     * or null if the hex is not in the palette.
     * Falls back to [nearestEntry] for legacy tags with off-palette hex values.
     */
    fun nameForHex(hex: String, getString: (Int) -> String): String? {
        val clean = hex.uppercase().trimStart('#')
        return entries.firstOrNull { it.hex == clean }?.let { getString(it.nameRes) }
    }

    /**
     * Returns the palette entry whose RGB colour is closest to the given
     * RGB values using Euclidean distance in RGB space.
     * Used by VisionAnalyzer to map a detected dominant colour to the palette.
     */
    fun nearestEntry(r: Int, g: Int, b: Int): Entry {
        return entries.minByOrNull { entry ->
            val dr = (entry.r - r).toDouble()
            val dg = (entry.g - g).toDouble()
            val db = (entry.b - b).toDouble()
            sqrt(dr * dr + dg * dg + db * db)
        } ?: entries.last()
    }

    /**
     * Convenience overload: accepts a packed ARGB int (e.g. from Android's
     * Color class or a Bitmap pixel) and returns the nearest palette entry.
     */
    fun nearestEntryFromArgb(argb: Int): Entry {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8)  and 0xFF
        val b =  argb         and 0xFF
        return nearestEntry(r, g, b)
    }
}