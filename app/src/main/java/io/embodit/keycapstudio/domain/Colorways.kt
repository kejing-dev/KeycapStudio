package io.embodit.keycapstudio.domain

import org.json.JSONArray

/** Keycap colours as ARGB ints: [bg] the cap, [fg] the legend. */
data class KeyColor(val bg: Int, val fg: Int)

/**
 * One recommended colorway (design `content.colorways.sets`, hex from crsnbrt/keysim, MIT). [swatches] holds
 * base / mods / accent / accent2..4; [override] maps a keycode or token (`F5-F8`) to a swatch name.
 */
data class Colorway(
    val id: String,
    val zh: String,
    val en: String,
    val inspiredBy: String,
    val swatches: Map<String, KeyColor>,
    val override: Map<String, String>,
) {
    /** The three colour bars on the card: base, mods, accent backgrounds. */
    val bars get() = listOf("base", "mods", "accent").map { swatches.getValue(it).bg }

    /** Colour of every key of [layout] (numpad colours too, so 87 ↔ 104 keeps them). */
    fun colorsFor(keys: Collection<String>, accents: Set<String>): Map<String, KeyColor> {
        val expanded = HashMap<String, String>()
        override.forEach { (k, v) -> (TOKENS[k] ?: listOf(k)).forEach { expanded[it] = v } }
        return keys.associateWith { id ->
            val swatch = expanded[id] ?: when (Groups.group(id, accents)) {
                KeyGroup.ALPHAS -> "base"
                KeyGroup.MODS, KeyGroup.SPACE -> "mods"
                KeyGroup.ACCENTS -> "accent"
            }
            swatches[swatch] ?: swatches.getValue("base")
        }
    }

    companion object {
        /** design `keyIdScheme.overrideTokens` */
        val TOKENS = mapOf(
            "F5-F8" to listOf("KC_F5", "KC_F6", "KC_F7", "KC_F8"),
            "arrows" to PaletteTarget.ARROW_IDS.toList(),
            "bottomMods" to listOf("KC_LCTL", "KC_LGUI", "KC_LALT", "KC_RALT", "KC_RGUI", "KC_APP", "KC_RCTL"),
            "KC_GESC" to listOf("KC_ESC"),
        )

        fun parse(json: String): List<Colorway> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sw = LinkedHashMap<String, KeyColor>()
                for (name in listOf("base", "mods", "accent", "accent2", "accent3", "accent4")) {
                    val a = o.optJSONArray(name) ?: continue
                    sw[name] = KeyColor(Hex.parse(a.getString(0)), Hex.parse(a.getString(1)))
                }
                val ov = o.optJSONObject("override")
                val override = ov?.keys()?.asSequence()?.associateWith { ov.getString(it) } ?: emptyMap()
                Colorway(o.getString("id"), o.getString("zh"), o.getString("en"), o.getString("inspiredBy"), sw, override)
            }
        }
    }
}

object Hex {
    fun parse(s: String): Int {
        val h = s.trim().removePrefix("#")
        require(h.length == 6) { "bad hex $s" }
        return (0xFF shl 24) or h.toInt(16)
    }

    fun parseOrNull(s: String): Int? = runCatching { parse(s) }.getOrNull()

    fun format(c: Int): String = "#%06X".format(c and 0xFFFFFF)

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    /** WCAG relative luminance. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(r(c)) + 0.7152 * ch(g(c)) + 0.0722 * ch(b(c))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** design `palette.legendColor` 「自动高对比」: the darker / lighter legend with the higher contrast. */
    fun autoLegend(bg: Int): Int {
        val dark = parse("#1A1A1A"); val light = parse("#F4F1EA")
        return if (contrast(bg, dark) >= contrast(bg, light)) dark else light
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun l(x: Int, y: Int) = (x + (y - x) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (l(r(a), r(b)) shl 16) or (l(g(a), g(b)) shl 8) or l(b(a), b(b))
    }

    /** HSV → ARGB; h 0–360, s / v 0–1. */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun q(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (q(r1) shl 16) or (q(g1) shl 8) or q(b1)
    }

    fun toHsv(c: Int): FloatArray {
        val r = r(c) / 255f; val g = g(c) / 255f; val b = b(c) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }
}

/** design `content.palette.picker`: GMK classic colours (Deskthority samples, approximations). */
val GMK_CLASSIC: List<Pair<String, Int>> = listOf(
    "L9" to "#E3E5D8", "U9" to "#B9BAB4", "CR" to "#1D3327", "N7" to "#058550", "V1" to "#EE4942", "V4" to "#0255BB",
    "V0" to "#A8AEAC", "2M" to "#D7E1EB", "2B" to "#858A8D", "CV" to "#FDE731", "BJ" to "#9A9790", "N5" to "#008DEA",
    "P2" to "#FCAE5C", "L2" to "#F7F4E6", "N2" to "#9FA9B3", "AE" to "#65AF4C", "N8" to "#514A38", "CB" to "#A7A398",
    "N3" to "#253928", "CC" to "#6C736B", "L6" to "#C9C2B0", "3C" to "#D89793", "3A" to "#87BC9E", "3B" to "#81A291",
    "3K" to "#D9DCE1", "1M" to "#CED1E4",
).map { it.first to Hex.parse(it.second) }
