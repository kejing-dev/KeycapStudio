package io.embodit.keycapstudio.domain

import org.json.JSONObject

enum class Style { SOLID, GRADIENT, THEMED, SIDE, SHINE }
enum class Fill { SOLID, GRADIENT, THEMED }
enum class CapMaterial { PBT, ABS, PC }
enum class Profile { OEM, CHERRY }
enum class SwitchType { LINEAR, TACTILE, CLICKY, TOPRE, SILENT }
enum class LegendMode { FOLLOW, AUTO, CUSTOM }

/** Theme pattern ids (design `content.patterns.themed`) plus the user's own picture. */
val THEMES = listOf("dots", "stripes", "sakura", "pixel", "stars", "camo", "marble", "grid")
const val THEME_IMAGE = "image"

/**
 * A keyboard scheme (design `rules.save`). Immutable: every edit makes a copy, which is what the undo stack keeps.
 * [colors] holds all 104 keys so the numpad colours survive 87 ↔ 104.
 */
data class Scheme(
    val name: String = "",
    val layout: Int = 87,
    val colors: Map<String, KeyColor> = emptyMap(),
    val accents: Set<String> = Groups.ACCENT_DEFAULT,
    val colorwayId: String? = "olivia",
    val style: Style = Style.SOLID,
    /** what the caps show under side legends / shine-through */
    val fill: Fill = Fill.SOLID,
    val gradientDir: Int = 2,
    val gradientA: Int = Hex.parse("#E1DBD1"),
    val gradientB: Int = Hex.parse("#F1BEB0"),
    val theme: String = "sakura",
    val themeA: Int = Hex.parse("#E1DBD1"),
    val themeB: Int = Hex.parse("#F1BEB0"),
    val image: String? = null,
    val backlight: Int = Hex.parse("#40D3D9"),
    val brightness: Float = 0.8f,
    val breathe: Boolean = true,
    val material: CapMaterial = CapMaterial.PBT,
    val profile: Profile = Profile.CHERRY,
    val caseColor: Int = Hex.parse("#3A3D42"),
    val switch: SwitchType = SwitchType.LINEAR,
    val legendMode: LegendMode = LegendMode.FOLLOW,
    val customLegend: Int = Hex.parse("#2B2B2B"),
    val yaw: Float = 0f,
    val pitch: Float = 7f,
) {
    /** The legend colour actually drawn on [id]. */
    fun legendOf(id: String): Int {
        val c = colors[id] ?: return customLegend
        return when (legendMode) {
            LegendMode.FOLLOW -> c.fg
            LegendMode.AUTO -> Hex.autoLegend(c.bg)
            LegendMode.CUSTOM -> customLegend
        }
    }

    /** Distinct cap colours in use (palette 「本方案已用」, ≤ 10). */
    fun usedColors(layout: Layout): List<Int> =
        layout.keys.mapNotNull { colors[it.id]?.bg }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }.take(10)

    val effectiveFill get() = when (style) {
        Style.SOLID -> Fill.SOLID
        Style.GRADIENT -> Fill.GRADIENT
        Style.THEMED -> Fill.THEMED
        Style.SIDE, Style.SHINE -> fill
    }

    /** Shine-through switches the caps to translucent PC (design `patterns.shineThrough`). */
    val effectiveMaterial get() = if (style == Style.SHINE) CapMaterial.PC else material

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemeVersion", 1)
        put("name", name); put("layout", layout)
        put("keyColors", JSONObject().apply { colors.forEach { (k, v) -> put(k, org.json.JSONArray(listOf(Hex.format(v.bg), Hex.format(v.fg)))) } })
        put("accents", org.json.JSONArray(accents.sorted()))
        put("colorway", colorwayId ?: JSONObject.NULL)
        put("pattern", style.name.lowercase()); put("fill", fill.name.lowercase())
        put("patternParams", JSONObject().apply {
            put("gradientDir", gradientDir); put("gradientA", Hex.format(gradientA)); put("gradientB", Hex.format(gradientB))
            put("theme", theme); put("themeA", Hex.format(themeA)); put("themeB", Hex.format(themeB))
            put("backlight", Hex.format(backlight)); put("brightness", brightness.toDouble()); put("breathe", breathe)
        })
        put("importedImage", image ?: JSONObject.NULL)
        put("material", material.name.lowercase()); put("profile", profile.name.lowercase())
        put("caseColor", Hex.format(caseColor)); put("switch", switch.name.lowercase())
        put("legendColor", legendMode.name.lowercase()); put("customLegend", Hex.format(customLegend))
        put("pose", JSONObject().apply { put("yaw", yaw.toDouble()); put("pitch", pitch.toDouble()) })
    }

    companion object {
        /** Throws on anything malformed (callers show S19). Unknown keys are ignored, missing ones take defaults. */
        fun fromJson(o: JSONObject): Scheme {
            require(o.optInt("schemeVersion", 0) == 1) { "schemeVersion" }
            val d = Scheme()
            val kc = o.getJSONObject("keyColors")
            val colors = kc.keys().asSequence().associateWith { k ->
                val a = kc.getJSONArray(k); KeyColor(Hex.parse(a.getString(0)), Hex.parse(a.getString(1)))
            }
            require(colors.isNotEmpty()) { "keyColors" }
            val pp = o.optJSONObject("patternParams") ?: JSONObject()
            fun col(src: JSONObject, k: String, def: Int) = src.optString(k, "").let { Hex.parseOrNull(it) ?: def }
            val acc = o.optJSONArray("accents")
            val pose = o.optJSONObject("pose") ?: JSONObject()
            val layout = o.getInt("layout")
            require(layout == 87 || layout == 104) { "layout" }
            return d.copy(
                name = o.optString("name", ""), layout = layout, colors = colors,
                accents = acc?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: d.accents,
                colorwayId = if (o.isNull("colorway")) null else o.optString("colorway", null),
                style = o.en("pattern", d.style), fill = o.en("fill", d.fill),
                gradientDir = pp.optInt("gradientDir", d.gradientDir).coerceIn(0, 3),
                gradientA = col(pp, "gradientA", d.gradientA), gradientB = col(pp, "gradientB", d.gradientB),
                theme = pp.optString("theme", d.theme).takeIf { it in THEMES || it == THEME_IMAGE } ?: d.theme,
                themeA = col(pp, "themeA", d.themeA), themeB = col(pp, "themeB", d.themeB),
                backlight = col(pp, "backlight", d.backlight),
                brightness = pp.optDouble("brightness", d.brightness.toDouble()).toFloat().coerceIn(0f, 1f),
                breathe = pp.optBoolean("breathe", d.breathe),
                image = if (o.isNull("importedImage")) null else o.optString("importedImage", null),
                material = o.en("material", d.material), profile = o.en("profile", d.profile),
                caseColor = col(o, "caseColor", d.caseColor), switch = o.en("switch", d.switch),
                legendMode = o.en("legendColor", d.legendMode), customLegend = col(o, "customLegend", d.customLegend),
                yaw = pose.optDouble("yaw", 0.0).toFloat(), pitch = pose.optDouble("pitch", 7.0).toFloat().coerceIn(0f, 60f),
            )
        }
    }
}

private inline fun <reified E : Enum<E>> JSONObject.en(k: String, def: E): E =
    runCatching { enumValueOf<E>(getString(k).uppercase()) }.getOrDefault(def)
