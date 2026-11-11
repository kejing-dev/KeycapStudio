package io.embodit.keycapstudio.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextField
import io.embodit.keycapstudio.KsApp
import io.embodit.keycapstudio.R
import io.embodit.keycapstudio.domain.CapMaterial
import io.embodit.keycapstudio.domain.GMK_CLASSIC
import io.embodit.keycapstudio.domain.Hex
import io.embodit.keycapstudio.domain.LegendMode
import io.embodit.keycapstudio.domain.PaletteTarget
import io.embodit.keycapstudio.domain.Profile
import io.embodit.keycapstudio.domain.Repo
import io.embodit.keycapstudio.domain.SavedScheme
import io.embodit.keycapstudio.domain.SingleMode
import io.embodit.keycapstudio.domain.StudioStore
import io.embodit.keycapstudio.domain.Style
import io.embodit.keycapstudio.domain.SwitchType
import io.embodit.keycapstudio.domain.THEMES
import io.embodit.keycapstudio.domain.THEME_IMAGE
import io.embodit.keycapstudio.domain.Typing
import io.embodit.keycapstudio.platform.KsLog
import io.embodit.keycapstudio.scene.Art
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private fun zh(res: Resources) = res.configuration.locales[0].language == "zh"

// ------------------------------------------------------------------------------------------- colorways (S03)
@Composable
fun ColumnScope.ColorwaysPage() {
    val res = LocalKsResources.current
    TopBar(rs(R.string.tab_colorways))
    var page by rememberSaveable { mutableIntStateOf(0) }
    val all = StudioStore.colorways
    val pages = (all.size + 5) / 6
    PageBody(bottomGap = true) {
        Seg(listOf(87 to rs(R.string.layout_87), 104 to rs(R.string.layout_104)), StudioStore.scheme.layout, "layout") { StudioStore.setLayout(it); KsLog.i("TAP layout $it") }
        all.drop(page * 6).take(6).chunked(3).forEach { row ->
            Line { row.forEach { cw -> ColorwayCard(cw, Modifier.weight(1f)) { StudioStore.applyColorway(cw.id); notifyColorway(res, cw.id); KsLog.i("TAP colorway ${cw.id}") } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } }
        }
        Line {
            KButton(rs(R.string.prev), "cw_prev", enabled = page > 0, icon = R.drawable.ic_chevron_left) { page-- }
            Label(rs(R.string.page_of, page + 1, pages), "cw_page", Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            KButton(rs(R.string.next), "cw_next", enabled = page < pages - 1, icon = R.drawable.ic_chevron_right) { page++ }
        }
        Label(rs(R.string.cw_hint), "cw_hint", secondary = true)
        Label(rs(R.string.footnote_colors), "cw_footnote", secondary = true, style = PicoTheme.typography.labelMedium)
    }
}

/** Debug: open the colorway pager on the page holding [id] (QA captures both pages, S03). */
object ColorwayPager { var request by mutableStateOf<Int?>(null) }

@Composable
private fun ColorwayCard(cw: io.embodit.keycapstudio.domain.Colorway, modifier: Modifier, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    val selected = StudioStore.scheme.colorwayId == cw.id
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(c.fillTertiary)
            .border(if (selected) 3.dp else 0.dp, if (selected) c.interaction else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))) { cw.bars.forEach { Box(Modifier.weight(1f).height(30.dp).background(Color(it))) } }
        Label(cw.zh, "card/${cw.id}/zh", style = PicoTheme.typography.bodyLarge)
        Label(cw.en, "card/${cw.id}/en", style = PicoTheme.typography.bodyMedium)
        Label(rs(R.string.inspired_by, cw.inspiredBy), "card/${cw.id}/src", secondary = true, style = PicoTheme.typography.labelMedium)
    }
}

// ------------------------------------------------------------------------------------------- palette (S04 / S05)
@Composable
fun ColumnScope.PalettePage() {
    TopBar(rs(R.string.tab_palette))
    val s = StudioStore.scheme
    PageBody {
        SectionTitle(rs(R.string.target), "palette/target")
        val targets = PaletteTarget.entries.filter { it != PaletteTarget.NUMPAD || s.layout == 104 }
        targets.chunked(3).forEach { row ->
            Line { row.forEach { t -> TargetChip(t, Modifier.weight(1f)) }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } }
        }
        if (StudioStore.single) {
            Seg(listOf(SingleMode.PAINT to rs(R.string.single_paint), SingleMode.ACCENT to rs(R.string.single_accent)), StudioStore.singleMode, "single_mode") { StudioStore.singleMode = it }
            Label(rs(if (StudioStore.singleMode == SingleMode.PAINT) R.string.single_hint else R.string.single_accent_hint), "palette/single_hint", secondary = true)
        }
        ColorPicker(onPick = { c, final -> StudioStore.paint = c; if (final) paintNow(c) })
        Line {
            Label(rs(R.string.legend_color), "palette/legend", Modifier.width(96.dp))
            Seg(listOf(LegendMode.FOLLOW to rs(R.string.legend_follow), LegendMode.AUTO to rs(R.string.legend_auto), LegendMode.CUSTOM to rs(R.string.legend_custom)),
                s.legendMode, "legend", Modifier.weight(1f)) { m -> StudioStore.setLegendMode(m, if (m == LegendMode.CUSTOM) StudioStore.paint else null) }
        }
        SectionTitle(rs(R.string.gmk_classic), "palette/gmk")
        GMK_CLASSIC.chunked(9).forEach { row -> Line { row.forEach { (code, col) -> Swatch(col, StudioStore.paint == col, "GMK $code ${Hex.format(col)}") { StudioStore.paint = col; paintNow(col) } } } }
        val used = s.usedColors(StudioStore.layout)
        if (used.isNotEmpty()) {
            SectionTitle(rs(R.string.used_colors), "palette/used")
            Line { used.forEach { col -> Swatch(col, StudioStore.paint == col, Hex.format(col)) { StudioStore.paint = col; paintNow(col) } } }
        }
    }
}

private fun paintNow(c: Int) {
    if (StudioStore.single) { KsLog.i("PAINT colour=${Hex.format(c)} (single mode: tap a key)"); return }
    StudioStore.paintTarget(c)
    KsLog.i("PAINT target=${StudioStore.target} colour=${Hex.format(c)}")
}

@Composable
private fun TargetChip(t: PaletteTarget, modifier: Modifier) {
    val label = when (t) {
        PaletteTarget.ALPHAS -> R.string.t_alphas; PaletteTarget.MODS -> R.string.t_mods; PaletteTarget.ACCENTS -> R.string.t_accents
        PaletteTarget.SPACE -> R.string.t_space; PaletteTarget.ARROWS -> R.string.t_arrows; PaletteTarget.NAV -> R.string.t_nav
        PaletteTarget.NUMPAD -> R.string.t_numpad; PaletteTarget.FROW -> R.string.t_frow; PaletteTarget.NUMROW -> R.string.t_numrow
        PaletteTarget.CASE -> R.string.t_case; PaletteTarget.ALL -> R.string.t_all; PaletteTarget.SINGLE -> R.string.t_single
    }
    KButton(rs(label), "target_${t.name.lowercase()}", modifier, primary = StudioStore.target == t) { StudioStore.target = t; KsLog.i("TAP target $t") }
}

/** Hue ring (tap to pick) + saturation / brightness sliders + HEX field; [onPick] (colour, final). */
@Composable
fun ColorPicker(onPick: (Int, Boolean) -> Unit) {
    val hsv = Hex.toHsv(StudioStore.paint)
    var hex by remember(StudioStore.paint) { mutableStateOf(Hex.format(StudioStore.paint)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(180.dp).pointerInput(Unit) {
                detectTapGestures { p ->
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val deg = Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())).toFloat().let { if (it < 0) it + 360f else it }
                    val cur = Hex.toHsv(StudioStore.paint)
                    onPick(Hex.hsv(deg, cur[1].coerceAtLeast(0.35f), cur[2].coerceAtLeast(0.45f)), true)
                }
            }) {
                val ring = size.minDimension * 0.12f
                drawCircle(Brush.sweepGradient((0..12).map { Color(Hex.hsv(it * 30f, 1f, 1f)) }), radius = size.minDimension / 2f - ring / 2f, style = Stroke(ring))
                val a = Math.toRadians(hsv[0].toDouble())
                val r = size.minDimension / 2f - ring / 2f
                drawCircle(Color.White, radius = ring * 0.55f, center = Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat()), style = Stroke(4f))
            }
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(42.dp)).background(Color(StudioStore.paint)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SliderRow(rs(R.string.sat), hsv[1] * 100f, 0f..100f, "${(hsv[1] * 100).toInt()}%", "sat", onChange = { onPick(Hex.hsv(hsv[0], it / 100f, hsv[2]), false) }, onDone = { onPick(StudioStore.paint, true) })
            SliderRow(rs(R.string.`val`), hsv[2] * 100f, 0f..100f, "${(hsv[2] * 100).toInt()}%", "val", onChange = { onPick(Hex.hsv(hsv[0], hsv[1], it / 100f), false) }, onDone = { onPick(StudioStore.paint, true) })
            Line {
                TextField(value = hex, onValueChange = { t -> hex = t.take(7); Hex.parseOrNull(hex)?.let { onPick(it, false) } }, modifier = Modifier.weight(1f).heightIn(min = RowH), singleLine = true,
                    placeholder = { Text("#RRGGBB") })
                KButton(rs(R.string.apply_color), "apply_hex", primary = true) { Hex.parseOrNull(hex)?.let { onPick(it, true) } }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- patterns (S06 / S14)
@Composable
fun ColumnScope.PatternsPage() {
    TopBar(rs(R.string.tab_patterns))
    val resP = LocalKsResources.current
    val s = StudioStore.scheme
    PageBody {
        // five styles do not fit one segmented row in English (OVERFLOW seg/style): two rows of worded buttons
        listOf(listOf(Style.SOLID to R.string.st_solid, Style.GRADIENT to R.string.st_gradient, Style.THEMED to R.string.st_themed),
            listOf(Style.SIDE to R.string.st_side, Style.SHINE to R.string.st_shine)).forEach { row ->
            Line { row.forEach { (st, name) -> KButton(rs(name), "style_${st.name.lowercase()}", Modifier.weight(1f), primary = s.style == st) { StudioStore.setStyle(st); KsLog.i("TAP style $st") } }
                if (row.size < 3) Spacer(Modifier.weight(1f)) }
        }
        // short styles get a preview card and the legend colour row so the page is not mostly blank (q1-1, D44)
        if (s.style == Style.SOLID || s.style == Style.SIDE || s.style == Style.SHINE) KeycapPreview(s)
        if (s.style == Style.SOLID || s.style == Style.SIDE) {
            Line {
                Label(rs(R.string.legend_color), "patterns/legend", Modifier.width(96.dp))
                Seg(listOf(LegendMode.FOLLOW to rs(R.string.legend_follow), LegendMode.AUTO to rs(R.string.legend_auto), LegendMode.CUSTOM to rs(R.string.legend_custom)),
                    s.legendMode, "pat_legend", Modifier.weight(1f)) { m -> StudioStore.setLegendMode(m, if (m == LegendMode.CUSTOM) StudioStore.paint else null) }
            }
            KButton(rs(R.string.go_palette), "go_palette", Modifier.fillMaxWidth(), icon = R.drawable.ic_tune) { PanelNav.open(Page.PALETTE, "patterns") }
        }
        when (s.style) {
            Style.SOLID -> Label(rs(R.string.solid_hint), "patterns/solid_hint", secondary = true)
            Style.GRADIENT -> {
                Line { Label(rs(R.string.grad_dir), "patterns/dir", Modifier.width(80.dp))
                    Seg(listOf(0 to rs(R.string.gd_lr), 1 to rs(R.string.gd_tb), 2 to rs(R.string.gd_diag), 3 to rs(R.string.gd_radial)), s.gradientDir, "grad_dir", Modifier.weight(1f)) { d -> StudioStore.update { it.copy(gradientDir = d) } } }
                EndColors(s.gradientA, s.gradientB, { c -> StudioStore.update { it.copy(gradientA = c) } }, { c -> StudioStore.update { it.copy(gradientB = c) } })
            }
            Style.THEMED -> {
                val names = mapOf("dots" to R.string.th_dots, "stripes" to R.string.th_stripes, "sakura" to R.string.th_sakura, "pixel" to R.string.th_pixel,
                    "stars" to R.string.th_stars, "camo" to R.string.th_camo, "marble" to R.string.th_marble, "grid" to R.string.th_grid)
                (THEMES + listOfNotNull(THEME_IMAGE.takeIf { s.image != null })).chunked(3).forEach { row ->
                    Line { row.forEach { id -> KButton(if (id == THEME_IMAGE) rs(R.string.th_image) else rs(names.getValue(id)), "theme_$id", Modifier.weight(1f), primary = s.theme == id) { StudioStore.update { it.copy(theme = id) }; KsLog.i("TAP theme $id") } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } }
                }
                EndColors(s.themeA, s.themeB, { c -> StudioStore.update { it.copy(themeA = c) } }, { c -> StudioStore.update { it.copy(themeB = c) } })
                KButton(rs(R.string.import_image), "import_image", Modifier.fillMaxWidth(), icon = R.drawable.ic_image) { SchemeActions.importImage(resP) }
                Label(rs(R.string.import_image_hint), "patterns/import_hint", secondary = true)
            }
            Style.SIDE -> Label(rs(R.string.side_hint), "patterns/side_hint", secondary = true)
            Style.SHINE -> {
                Line { Label(rs(R.string.backlight), "patterns/backlight", Modifier.weight(1f)); Swatch(s.backlight, false, "backlight") {}
                    KButton(rs(R.string.use_paint), "backlight_paint") { StudioStore.update { it.copy(backlight = StudioStore.paint) } } }
                SliderRow(rs(R.string.brightness), s.brightness * 100f, 0f..100f, "${(s.brightness * 100).toInt()}%", "brightness",
                    onChange = { v -> StudioStore.update { it.copy(brightness = v / 100f) } })
                Toggle(s.breathe, rs(R.string.breathe), "breathe") { b -> StudioStore.update { it.copy(breathe = b) } }
                Label(rs(R.string.shine_hint), "patterns/shine_hint", secondary = true)
            }
        }
    }
}

private val PREVIEW_KEYS = listOf("KC_ESC" to "Esc", "KC_Q" to "Q", "KC_W" to "W", "KC_E" to "E", "KC_ENT" to "Enter")

/** Five caps drawn flat in the current colours: legends on top (solid / shine) or on the front strip (side print). */
@Composable
private fun KeycapPreview(s: io.embodit.keycapstudio.domain.Scheme) {
    val c = PicoTheme.colorScheme
    val side = s.style == Style.SIDE; val shine = s.style == Style.SHINE
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fillTertiary).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(rs(R.string.preview_title), "patterns/preview", secondary = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PREVIEW_KEYS.forEach { (id, text) ->
                val bg = s.colors[id]?.bg ?: 0xFF808080.toInt()
                val fg = if (shine) s.backlight else s.legendOf(id)
                val wide = id == "KC_ENT"
                Column(Modifier.weight(if (wide) 1.6f else 1f).height(104.dp).clip(RoundedCornerShape(12.dp)).background(Color(Hex.lerp(bg, 0xFF000000.toInt(), 0.25f)))) {
                    // top face
                    Box(Modifier.fillMaxWidth().weight(1f).padding(4.dp).clip(RoundedCornerShape(10.dp)).background(Color(bg)), contentAlignment = Alignment.Center) {
                        if (!side) Text(text, color = Color(fg), style = PicoTheme.typography.titleSmall.let { st ->
                            if (shine) st.copy(shadow = androidx.compose.ui.graphics.Shadow(Color(s.backlight), blurRadius = 12f)) else st }, maxLines = 1, softWrap = false, onTextLayout = fits("preview/$id"))
                    }
                    // front face
                    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                        if (side) Text(text, color = Color(fg), style = PicoTheme.typography.labelMedium, maxLines = 1, softWrap = false, onTextLayout = fits("preview/side/$id"))
                    }
                }
            }
        }
    }
}

@Composable
private fun EndColors(a: Int, b: Int, setA: (Int) -> Unit, setB: (Int) -> Unit) {
    Line { Label(rs(R.string.end_a), "patterns/end_a", Modifier.weight(1f)); Swatch(a, false, "end_a") {}; KButton(rs(R.string.use_paint), "end_a_paint") { setA(StudioStore.paint) } }
    Line { Label(rs(R.string.end_b), "patterns/end_b", Modifier.weight(1f)); Swatch(b, false, "end_b") {}; KButton(rs(R.string.use_paint), "end_b_paint") { setB(StudioStore.paint) } }
    Line { GMK_CLASSIC.take(9).forEach { (code, col) -> Swatch(col, StudioStore.paint == col, "GMK $code ${Hex.format(col)}", size = 40.dp) { StudioStore.paint = col } } }
}

// ------------------------------------------------------------------------------------------- material & sound (S07 / S11)
@Composable
fun ColumnScope.MaterialPage() {
    TopBar(rs(R.string.tab_material))
    val s = StudioStore.scheme
    PageBody {
        // S11: while a typing test runs its progress sits first on the page, not below the fold
        TypingStatus()
        Line { Label(rs(R.string.cap_material), "material/cap", Modifier.width(112.dp))
            Seg(listOf(CapMaterial.PBT to rs(R.string.m_pbt), CapMaterial.ABS to rs(R.string.m_abs), CapMaterial.PC to rs(R.string.m_pc)), s.material, "material", Modifier.weight(1f)) { m -> StudioStore.update { it.copy(material = m) }; KsLog.i("TAP material $m") } }
        Line { Label(rs(R.string.profile), "material/profile", Modifier.width(112.dp))
            Seg(listOf(Profile.OEM to rs(R.string.p_oem), Profile.CHERRY to rs(R.string.p_cherry)), s.profile, "profile", Modifier.weight(1f)) { p -> StudioStore.update { it.copy(profile = p) }; KsLog.i("TAP profile $p") } }
        SectionTitle(rs(R.string.case_color), "material/case")
        val cases = listOf("#3A3D42" to R.string.case_dark, "#C9CCD1" to R.string.case_silver, "#EFEFEA" to R.string.case_white)
        listOf(cases.take(2), cases.drop(2)).forEachIndexed { i, row ->
            Line {
                row.forEach { (hex, name) ->
                    val col = Hex.parse(hex)
                    KButton(rs(name), "case_$hex", Modifier.weight(1f), primary = s.caseColor == col) { StudioStore.update { it.copy(caseColor = col) } }
                }
                if (i == 1) KButton(rs(R.string.case_custom), "case_custom", Modifier.weight(1f)) { StudioStore.target = PaletteTarget.CASE; PanelNav.open(Page.PALETTE, "case_custom") }
            }
        }
        SectionTitle(rs(R.string.switch_type), "material/switch")
        listOf(SwitchType.LINEAR to R.string.sw_linear, SwitchType.TACTILE to R.string.sw_tactile, SwitchType.CLICKY to R.string.sw_clicky, SwitchType.TOPRE to R.string.sw_topre, SwitchType.SILENT to R.string.sw_silent).forEach { (t, name) ->
            Line {
                KButton(rs(name), "switch_${t.name.lowercase()}", Modifier.weight(1f), primary = s.switch == t) { StudioStore.update { it.copy(switch = t) }; KsLog.i("TAP switch $t") }
                KButton(rs(R.string.listen), "listen_${t.name.lowercase()}", icon = R.drawable.ic_volume_up) { listen(t) }
            }
        }
        VolumeRows()
        TryTyping()
    }
}

/** 「听一下」: three presses on F, J and space, 250 ms apart. */
private fun listen(t: SwitchType) = KsApp.scope.launch {
    KsLog.i("LISTEN $t")
    listOf("KC_F", "KC_J", "KC_SPC").forEach { id -> KsApp.sound.press(t, StudioStore.full.byId[id]); kotlinx.coroutines.delay(90); KsApp.sound.release(t, StudioStore.full.byId[id]); kotlinx.coroutines.delay(200) }
}

val SENTENCES = listOf(R.string.tt_s1, R.string.tt_s2, R.string.tt_s3)

fun typingIds(text: String): List<String> = text.mapNotNull { ch ->
    when {
        ch == ' ' -> "KC_SPC"
        ch.isLetter() -> "KC_${ch.uppercaseChar()}"
        ch.isDigit() -> "KC_$ch"
        else -> null
    }
}

@Composable
private fun TryTyping() {
    val res = LocalKsResources.current
    var pick by rememberSaveable { mutableIntStateOf(1) }
    SectionTitle(rs(R.string.try_typing), "material/typing")
    Seg(SENTENCES.mapIndexed { i, r -> i to res.getString(r) }, pick, "sentence") { pick = it }
    val t = StudioStore.typing
    Line {
        if (t == null || t.done) KButton(rs(R.string.tt_start), "tt_start", Modifier.weight(1f), primary = true) { startTyping(res.getString(SENTENCES[pick])) }
        else KButton(rs(R.string.tt_stop), "tt_stop", Modifier.weight(1f)) { StudioStore.typing = null; KsLog.i("TYPING stop") }
    }
}

@Composable
private fun TypingStatus() {
    val t = StudioStore.typing ?: return
    val c = PicoTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fillSecondary).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line {
            Label(rs(R.string.typing_now), "typing/title", Modifier.weight(1f), secondary = true)
            if (!t.done) KButton(rs(R.string.tt_stop), "tt_stop_top") { StudioStore.typing = null; KsLog.i("TYPING stop") }
        }
        val typed = t.text.take(t.index) + if (!t.done) "_" else ""
        Label(typed, "typing/progress", style = PicoTheme.typography.titleMedium)
        Label(if (t.done) rs(R.string.tt_done, "%.1f".format((t.endMs - t.startMs) / 1000f), t.presses) else rs(R.string.tt_progress), "typing/hint", secondary = !t.done)
    }
}

fun startTyping(text: String) {
    StudioStore.typing = Typing(text, typingIds(text))
    KsLog.i("TYPING start \"$text\"")
}

// ------------------------------------------------------------------------------------------- schemes (S12 / S13)
@Composable
fun ColumnScope.SchemesPage() {
    val context = LocalContext.current
    val res = LocalKsResources.current
    TopBar(rs(R.string.tab_schemes))
    var name by remember(StudioStore.scheme.name) { mutableStateOf(StudioStore.scheme.name) }
    val list = remember(SchemeActions.listSeq) { KsApp.repo.list() }
    var confirmDelete by PanelFlags.confirmDelete
    PageBody(bottomGap = true) {
        Line {
            TextField(value = name, onValueChange = { name = it.take(24); StudioStore.rename(name) }, modifier = Modifier.weight(1f).heightIn(min = RowH), singleLine = true,
                placeholder = { Text(rs(R.string.scheme_name)) })
            KButton(rs(R.string.save_as), "save_as") { SchemeActions.save(context, res, asNew = true) }
        }
        SectionTitle(rs(R.string.saved_list), "schemes/list")
        if (list.isEmpty()) EmptySchemes { SchemeActions.save(context, res, asNew = false) }
        list.forEach { item ->
            SchemeRow(item, confirmDelete == item.id, onLoad = { SchemeActions.load(res, item) }, onAskDelete = { confirmDelete = item.id },
                onCancel = { confirmDelete = null }, onDelete = { KsApp.repo.delete(item.id); confirmDelete = null; SchemeActions.listSeq++; KsLog.i("SCHEME delete ${item.id}") })
        }
        Line {
            KButton(rs(R.string.export_card), "export_card", Modifier.weight(1f), icon = R.drawable.ic_download) { SchemeActions.exportCard(context, res) }
            KButton(rs(R.string.export_json), "export_json", Modifier.weight(1f)) { SchemeActions.exportJson(res) }
        }
        KButton(rs(R.string.import_json), "import_json", Modifier.fillMaxWidth(), icon = R.drawable.ic_upload) { SchemeActions.importJson(res) }
        Label(rs(R.string.export_3d_hint), "schemes/3d_hint", secondary = true)
    }
}

/** Empty list (S12): a card with the current colours as three caps and a direct [保存] (q1-1: no 30 % blank page). */
@Composable
private fun EmptySchemes(onSave: () -> Unit) {
    val c = PicoTheme.colorScheme
    val s = StudioStore.scheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fillTertiary).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("KC_ESC", "KC_A", "KC_ENT").forEach { id ->
                Box(Modifier.size(width = if (id == "KC_ENT") 104.dp else 64.dp, height = 64.dp).clip(RoundedCornerShape(12.dp)).background(Color(s.colors[id]?.bg ?: 0xFF808080.toInt())))
            }
        }
        Label(rs(R.string.empty_title), "schemes/empty_title", style = PicoTheme.typography.titleSmall)
        Label(rs(R.string.empty_list), "schemes/empty", secondary = true, align = androidx.compose.ui.text.style.TextAlign.Center)
        KButton(rs(R.string.save), "empty_save", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.ic_save, onClick = onSave)
    }
}

@Composable
private fun SchemeRow(item: SavedScheme, confirming: Boolean, onLoad: () -> Unit, onAskDelete: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val thumb = remember(item.id, item.savedAt) { runCatching { BitmapFactory.decodeFile(item.thumb.path)?.asImageBitmap() }.getOrNull() }
    val c = PicoTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fillTertiary).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Line {
            if (thumb != null) Image(thumb, contentDescription = null, modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f)) {
                Label(item.name.ifBlank { rs(R.string.scheme_untitled) }, "schemes/row/name", style = PicoTheme.typography.bodyLarge)
                Label(rs(R.string.saved_at, if (item.layout == 104) rs(R.string.layout_104) else rs(R.string.layout_87), SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.savedAt))), "schemes/row/meta", secondary = true)
            }
        }
        if (!confirming) Line { KButton(rs(R.string.load), "load", Modifier.weight(1f), onClick = onLoad); KButton(rs(R.string.delete), "delete", Modifier.weight(1f), icon = R.drawable.ic_delete, onClick = onAskDelete) }
        else Confirm(rs(R.string.delete_confirm, item.name.ifBlank { rs(R.string.scheme_untitled) }), rs(R.string.confirm_delete), "delete", onCancel, onDelete)
    }
}

/** Scheme page actions; the debug broadcast calls these too (same path as the buttons). */
object SchemeActions {
    var listSeq by mutableIntStateOf(0)
    var lastSavedId: String? = null

    fun cardLines(res: Resources): List<String> {
        val s = StudioStore.scheme
        val cw = StudioStore.colorways.firstOrNull { it.id == s.colorwayId }
        val mat = res.getString(when (s.effectiveMaterial) { CapMaterial.PBT -> R.string.m_pbt; CapMaterial.ABS -> R.string.m_abs; CapMaterial.PC -> R.string.m_pc })
        val sw = res.getString(when (s.switch) { SwitchType.LINEAR -> R.string.sw_linear; SwitchType.TACTILE -> R.string.sw_tactile; SwitchType.CLICKY -> R.string.sw_clicky; SwitchType.TOPRE -> R.string.sw_topre; SwitchType.SILENT -> R.string.sw_silent })
        return listOf(
            res.getString(R.string.card_layout, res.getString(if (s.layout == 104) R.string.layout_104 else R.string.layout_87)),
            res.getString(R.string.card_material, mat, if (s.profile == Profile.OEM) "OEM" else "Cherry"),
            res.getString(R.string.card_switch, sw),
            res.getString(R.string.card_colorway, cw?.let { (if (zh(res)) it.zh else it.en) + " · " + res.getString(R.string.inspired_by, it.inspiredBy) } ?: res.getString(R.string.card_custom)),
        )
    }

    private fun card(context: Context, res: Resources, w: Int = 2560, h: Int = 1440) =
        Art.schemeCard(context, StudioStore.scheme, StudioStore.layout, KsApp.repo.importCopies, res.getString(R.string.app_name), cardLines(res), w, h)

    fun save(context: Context, res: Resources, asNew: Boolean) {
        val s = StudioStore.scheme.let { if (it.name.isBlank()) it.copy(name = res.getString(R.string.scheme_untitled)) else it }
        val c = card(context, res, 960, 540)
        val id = KsApp.repo.save(s, Art.thumb(c), if (asNew) null else lastSavedId?.takeIf { s.name == StudioStore.scheme.name })
        c.recycle()
        if (id == null) { StudioStore.notify(res.getString(R.string.full_list), error = true); return }
        lastSavedId = id; listSeq++
        if (StudioStore.scheme.name.isBlank()) StudioStore.rename(s.name)
        KsApp.sound.tick()
        StudioStore.notify(res.getString(R.string.saved, s.name))
        KsLog.i("SCHEME save id=$id name=${s.name} asNew=$asNew")
    }

    fun load(res: Resources, item: SavedScheme) {
        runCatching { KsApp.repo.load(item.id) }.onSuccess { StudioStore.load(it); lastSavedId = item.id; StudioStore.notify(res.getString(R.string.loaded, it.name)); KsLog.i("SCHEME load ${item.id}") }
            .onFailure { StudioStore.notify(res.getString(R.string.json_bad), error = true) }
    }

    fun exportCard(context: Context, res: Resources) = runCatching {
        val bmp = card(context, res)
        val out = ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); bmp.recycle()
        val name = "${StudioStore.scheme.name.ifBlank { "keycap" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")}_${System.currentTimeMillis() / 1000}.png"
        val path = KsApp.repo.export(name, "image/png", out.toByteArray())
        StudioStore.notify(res.getString(R.string.exported, path)); KsLog.i("EXPORT card $path")
    }.onFailure { KsLog.e("export card failed", it); StudioStore.notify(it.message ?: "export failed", error = true) }

    fun exportJson(res: Resources) = runCatching {
        val name = "${StudioStore.scheme.name.ifBlank { "keycap" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")}_${System.currentTimeMillis() / 1000}.json"
        val path = KsApp.repo.export(name, "application/json", StudioStore.scheme.toJson().toString(2).toByteArray())
        StudioStore.notify(res.getString(R.string.exported, path)); KsLog.i("EXPORT json $path")
    }.onFailure { KsLog.e("export json failed", it); StudioStore.notify(it.message ?: "export failed", error = true) }

    fun importJson(res: Resources) {
        val r = runCatching { KsApp.repo.importJson() }
        r.onSuccess { pair ->
            if (pair == null) { StudioStore.notify(res.getString(R.string.json_none), error = true); return }
            StudioStore.load(pair.second); StudioStore.notify(res.getString(R.string.json_imported, pair.second.name.ifBlank { pair.first })); KsLog.i("IMPORT json ${pair.first}")
        }.onFailure { KsLog.w("IMPORT json bad: $it"); StudioStore.notify(res.getString(R.string.json_bad), error = true) }
    }

    fun importImage(res: Resources) {
        runCatching { KsApp.repo.importImage() }.onSuccess { name ->
            if (name == null) { StudioStore.notify(res.getString(R.string.import_none), error = true); return }
            StudioStore.update { it.copy(style = Style.THEMED, fill = io.embodit.keycapstudio.domain.Fill.THEMED, theme = THEME_IMAGE, image = name) }
            StudioStore.notify(res.getString(R.string.import_image_ok)); KsLog.i("IMPORT image $name")
        }.onFailure { KsLog.w("IMPORT image failed: $it"); StudioStore.notify(res.getString(R.string.import_failed), error = true) }
    }
}
