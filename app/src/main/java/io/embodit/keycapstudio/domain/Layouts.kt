package io.embodit.keycapstudio.domain

import org.json.JSONArray
import org.json.JSONObject

/** Keycap row profile (design `content.keycapGeometry.rows`). KLE row 0 is the F row, the bottom row uses R4. */
enum class CapRow { F, R1, R2, R3, R4 }

/**
 * One key of a layout. [x], [y], [w], [h] are KLE units (1u = 19.05 mm), [x]/[y] the top-left corner with y growing
 * away from the user. [legend] is the raw KLE label (`"!\n1"` = shifted on top, base below).
 */
data class Key(
    val id: String,
    val legend: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val row: CapRow,
    val numpad: Boolean,
) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
}

/** A parsed layout: 104 (ANSI full size) or 87 (TKL = 104 without the numpad). */
class Layout(val size: Int, val keys: List<Key>) {
    val widthU: Float = keys.maxOf { it.x + it.w }
    val heightU: Float = keys.maxOf { it.y + it.h }
    val byId: Map<String, Key> = keys.associateBy { it.id }
    val boardMm get() = Pair(widthU * U_MM, heightU * U_MM)

    companion object {
        const val U_MM = 19.05f
    }
}

object Layouts {
    /** The 17 numpad keys (design `keyIdScheme.count`): removing them turns 104 into 87. */
    val NUMPAD = setOf(
        "KC_NLCK", "KC_PSLS", "KC_PAST", "KC_PMNS", "KC_P7", "KC_P8", "KC_P9", "KC_PPLS",
        "KC_P4", "KC_P5", "KC_P6", "KC_P1", "KC_P2", "KC_P3", "KC_PENT", "KC_P0", "KC_PDOT",
    )

    private val ROWS = listOf(CapRow.F, CapRow.R1, CapRow.R2, CapRow.R3, CapRow.R4, CapRow.R4)

    /** Parses `assets/layouts/ansi104.json` ({"kle": [...rows], "ids": [...rows of keycodes]}). */
    fun parse(json: String): Pair<Layout, Layout> {
        val root = JSONObject(json)
        val kle = root.getJSONArray("kle")
        val ids = root.getJSONArray("ids")
        val keys = ArrayList<Key>()
        var y = 0f
        for (r in 0 until kle.length()) {
            val row: JSONArray = kle.getJSONArray(r)
            val idRow = ids.getJSONArray(r)
            var x = 0f
            var w = 1f
            var h = 1f
            var k = 0
            for (i in 0 until row.length()) {
                val item = row.get(i)
                if (item is JSONObject) {
                    x += item.optDouble("x", 0.0).toFloat()
                    y += item.optDouble("y", 0.0).toFloat()
                    w = item.optDouble("w", w.toDouble()).toFloat()
                    h = item.optDouble("h", h.toDouble()).toFloat()
                    continue
                }
                val id = idRow.getString(k++)
                keys += Key(id, item as String, x, y, w, h, ROWS[r], id in NUMPAD)
                x += w
                w = 1f
                h = 1f
            }
            require(k == idRow.length()) { "row $r: ${idRow.length()} ids for $k keys" }
            y += 1f
        }
        val full = Layout(104, keys)
        val tkl = Layout(87, keys.filterNot { it.numpad })
        return full to tkl
    }
}

/** Mutually exclusive groups (design `layouts.ansi104.groups`): alphas + mods + accents + space = 87 / 104. */
enum class KeyGroup { ALPHAS, MODS, ACCENTS, SPACE }

object Groups {
    val ACCENT_DEFAULT = setOf("KC_ESC", "KC_ENT")
    private val MOD_IDS = setOf(
        "KC_TAB", "KC_CAPS", "KC_LSFT", "KC_RSFT", "KC_LCTL", "KC_LGUI", "KC_LALT", "KC_RALT", "KC_RGUI", "KC_APP",
        "KC_RCTL", "KC_BSPC", "KC_PSCR", "KC_SLCK", "KC_PAUS", "KC_INS", "KC_HOME", "KC_PGUP", "KC_DEL", "KC_END",
        "KC_PGDN", "KC_UP", "KC_DOWN", "KC_LEFT", "KC_RGHT",
        "KC_NLCK", "KC_PSLS", "KC_PAST", "KC_PMNS", "KC_PPLS", "KC_PENT",
    ) + (1..12).map { "KC_F$it" }

    /** Base group of a key, before any per-scheme accent membership change. */
    fun baseGroup(id: String): KeyGroup = when {
        id in ACCENT_DEFAULT -> KeyGroup.ACCENTS
        id == "KC_SPC" -> KeyGroup.SPACE
        id in MOD_IDS -> KeyGroup.MODS
        else -> KeyGroup.ALPHAS
    }

    /** Group with the scheme's accent membership applied (single-key mode can move keys in and out of accents). */
    fun group(id: String, accents: Set<String>): KeyGroup = when {
        id in accents -> KeyGroup.ACCENTS
        id in ACCENT_DEFAULT -> KeyGroup.MODS  // Esc / Enter moved out of the accents fall back to the modifiers
        else -> baseGroup(id)
    }
}

/** Palette targets (design `content.palette.targets`). Shortcut targets cross groups and only recolour. */
enum class PaletteTarget {
    ALPHAS, MODS, ACCENTS, SPACE, ARROWS, NAV, NUMPAD, FROW, NUMROW, CASE, ALL, SINGLE;

    fun keys(layout: Layout, accents: Set<String>): List<String> = layout.keys.map { it.id }.filter { id ->
        when (this) {
            ALPHAS -> Groups.group(id, accents) == KeyGroup.ALPHAS
            MODS -> Groups.group(id, accents) == KeyGroup.MODS
            ACCENTS -> Groups.group(id, accents) == KeyGroup.ACCENTS
            SPACE -> id == "KC_SPC"
            ARROWS -> id in ARROW_IDS
            NAV -> id in NAV_IDS
            NUMPAD -> id in Layouts.NUMPAD
            FROW -> id.matches(Regex("KC_F\\d+"))
            NUMROW -> id in NUMROW_IDS
            CASE, SINGLE -> false
            ALL -> true
        }
    }

    companion object {
        val ARROW_IDS = setOf("KC_UP", "KC_DOWN", "KC_LEFT", "KC_RGHT")
        val NAV_IDS = setOf("KC_INS", "KC_HOME", "KC_PGUP", "KC_DEL", "KC_END", "KC_PGDN")
        val NUMROW_IDS = setOf("KC_GRV", "KC_1", "KC_2", "KC_3", "KC_4", "KC_5", "KC_6", "KC_7", "KC_8", "KC_9", "KC_0", "KC_MINS", "KC_EQL", "KC_BSPC")
    }
}
