package io.embodit.keycapstudio.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.keycapstudio.KsApp
import io.embodit.keycapstudio.PANEL_ID
import io.embodit.keycapstudio.PANEL_TAG
import io.embodit.keycapstudio.R
import io.embodit.keycapstudio.STUDIO_ID
import io.embodit.keycapstudio.domain.StudioStore
import io.embodit.keycapstudio.platform.KsLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/** Panel pages; Settings is the template's fixed last page (MainWindowTemplate, D8 / D26). */
enum class Page(val label: Int, val icon: Int) {
    COLORWAYS(R.string.tab_colorways, R.drawable.ic_palette),
    PALETTE(R.string.tab_palette, R.drawable.ic_brush),
    PATTERNS(R.string.tab_patterns, R.drawable.ic_texture),
    MATERIAL(R.string.tab_material, R.drawable.ic_tune),
    SCHEMES(R.string.tab_schemes, R.drawable.ic_bookmarks),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings),
}

/** Inline confirmations, process level so the debug broadcast can show them (S16, settings-confirm, schemes-confirm). */
object PanelFlags {
    val confirmExit = mutableStateOf(false)
    val confirmReset = mutableStateOf(false)
    val confirmDelete = mutableStateOf<String?>(null)
}

/** Current page, process level so the debug broadcast can open a page (same path as a TabBar tap). */
object PanelNav {
    var page by mutableStateOf(Page.COLORWAYS)
    fun open(p: Page, why: String) { page = p; KsLog.i("PAGE ${p.name.lowercase()} why=$why") }
}

/**
 * The control panel: one planar system window (MainWindowTemplate adapted: pages come from the app's own enum so the
 * debug broadcast reaches them; TabBar labels resolved in the app language before they go into the TabBar window).
 */
@Composable
fun PanelWindow() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> StudioStore.panelState = e.name; KsLog.i("PANEL ${e.name}") }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val res = localizedResources(context, KsApp.settings.lang)
    val labels = Page.entries.associateWith { res.getString(it.label) }
    CompositionLocalProvider(LocalKsResources provides res) {
        PicoTheme {
            val page = PanelNav.page
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    if (KsApp.coach >= 0) CoachCard()
                    when (page) {
                        Page.COLORWAYS -> ColorwaysPage()
                        Page.PALETTE -> PalettePage()
                        Page.PATTERNS -> PatternsPage()
                        Page.MATERIAL -> MaterialPage()
                        Page.SCHEMES -> SchemesPage()
                        Page.SETTINGS -> SettingsPage()
                    }
                }
            }
            TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
                Page.entries.forEach { p ->
                    item(
                        selected = page == p,
                        onClick = { PanelNav.open(p, "tab") },
                        mainContent = { Icon(painter = painterResource(p.icon), contentDescription = labels.getValue(p)) },
                        supportContent = { Text(labels.getValue(p)) },
                    )
                }
            }
            when (page) {
                Page.COLORWAYS -> Toolbar { KButton(rs(R.string.random_set), "random", icon = R.drawable.ic_shuffle) { val id = StudioStore.randomColorway(); notifyColorway(res, id) } }
                Page.SCHEMES -> Toolbar { KButton(rs(R.string.save), "toolbar_save", primary = true, icon = R.drawable.ic_save) { SchemeActions.save(context, res, asNew = false) } }
                else -> {}
            }
        }
    }
}

fun notifyColorway(res: android.content.res.Resources, id: String) {
    val cw = StudioStore.colorways.firstOrNull { it.id == id } ?: return
    val name = if (res.configuration.locales[0].language == "zh") cw.zh else cw.en
    StudioStore.notify(res.getString(R.string.cw_applied, name))
}

/** Page header (design `panels.panel.topbarCommon`): title + scheme name, then five worded actions. */
@Composable
fun TopBar(title: String) {
    val s = StudioStore.scheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(title, "topbar/title", style = PicoTheme.typography.titleLarge)
        Label(s.name.ifBlank { rs(R.string.scheme_untitled) }, "topbar/scheme", Modifier.weight(1f).padding(bottom = 3.dp), secondary = true)
    }
    Spacer(Modifier.height(10.dp))
    Line {
        ToolButton(if (StudioStore.detail) rs(R.string.act_detail_off) else rs(R.string.act_detail), R.drawable.ic_zoom_in, "detail", Modifier.weight(1.15f), on = StudioStore.detail) {
            StudioStore.detail = !StudioStore.detail; KsLog.i("TAP detail -> ${StudioStore.detail}")
        }
        ToolButton(rs(R.string.act_rotate), R.drawable.ic_rotate_90_degrees_cw, "rotate", Modifier.weight(1f), enabled = !StudioStore.detail) { StudioStore.rotate90(); KsLog.i("TAP rotate90 yaw=${StudioStore.scheme.yaw}") }
        ToolButton(rs(R.string.act_undo), R.drawable.ic_undo, "undo", Modifier.weight(1f), enabled = StudioStore.canUndo) { StudioStore.undo(); KsLog.i("TAP undo") }
        ToolButton(rs(R.string.act_redo), R.drawable.ic_redo, "redo", Modifier.weight(1f), enabled = StudioStore.canRedo) { StudioStore.redo(); KsLog.i("TAP redo") }
        ToolButton(if (StudioStore.muted) rs(R.string.act_unmute) else rs(R.string.act_mute), if (StudioStore.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, "mute", Modifier.weight(1.15f), on = StudioStore.muted) {
            StudioStore.muted = !StudioStore.muted; KsLog.i("TAP mute -> ${StudioStore.muted}")
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** A scrolling page body with the notice line under it; [bottomGap] keeps room for the system Toolbar. */
@Composable
fun ColumnScope.PageBody(bottomGap: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // short pages sit centred (blank ≤ 25 % of the window, D44); long pages scroll
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
    NoticeLine()
    if (bottomGap) Spacer(Modifier.height(64.dp))
}

/** Saved / exported / error line (design S13 / S19); errors use the error role and stay until 「知道了」. */
@Composable
fun NoticeLine() {
    val text = StudioStore.notice ?: return
    val seq = StudioStore.noticeSeq
    LaunchedEffect(seq) { if (!StudioStore.noticeError) { delay(3000); if (StudioStore.noticeSeq == seq) StudioStore.notice = null } }
    val c = PicoTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.fillTertiary).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(text, "notice", Modifier.weight(1f), color = if (StudioStore.noticeError) c.error else c.labelPrimary)
        if (StudioStore.noticeError) KButton(rs(R.string.got_it), "notice_ok") { StudioStore.notice = null }
    }
}

/** First-run tips inside the panel (design S18): three cards, skippable, again from Settings. */
@Composable
fun CoachCard() {
    val i = KsApp.coach
    val texts = listOf(R.string.coach_1, R.string.coach_2, R.string.coach_3)
    val c = PicoTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.fillSecondary).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(rs(R.string.coach_title, i + 1), "coach/title", secondary = true)
        Label(rs(texts[i]), "coach/text", style = PicoTheme.typography.bodyLarge)
        Line {
            Spacer(Modifier.weight(1f))
            KButton(rs(R.string.coach_skip), "coach_skip") { KsLog.i("TAP coach skip"); KsApp.finishCoach() }
            KButton(rs(if (i >= 2) R.string.coach_done else R.string.coach_next), if (i >= 2) "coach_done" else "coach_next", primary = true) { KsLog.i("TAP coach next"); if (i >= 2) KsApp.finishCoach() else KsApp.coach = i + 1 }
        }
    }
}

// ------------------------------------------------------------------------------------------- settings
@Composable
fun ColumnScope.SettingsPage() {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    var confirmExit by PanelFlags.confirmExit
    var confirmReset by PanelFlags.confirmReset
    val st = KsApp.settings
    Label(rs(R.string.tab_settings), "settings/title", style = PicoTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    PageBody {
        Line {
            Label(rs(R.string.language), "settings/lang", Modifier.padding(end = 8.dp))
            Seg(listOf(io.embodit.keycapstudio.domain.Lang.SYSTEM to rs(R.string.lang_system), io.embodit.keycapstudio.domain.Lang.ZH to rs(R.string.lang_zh), io.embodit.keycapstudio.domain.Lang.EN to rs(R.string.lang_en)),
                st.lang, "lang", Modifier.weight(1f)) { l -> KsApp.updateSettings { it.copy(lang = l) }; KsLog.i("LANG $l") }
        }
        VolumeRows()
        SliderRow(rs(R.string.default_tilt), st.defaultTilt, 0f..60f, rs(R.string.tilt_deg, st.defaultTilt.toInt()), "tilt",
            onChange = { v -> val snapped = (Math.round(v / 5f) * 5).toFloat(); KsApp.updateSettings { it.copy(defaultTilt = snapped) } },
            onDone = { StudioStore.setPitch(KsApp.settings.defaultTilt); KsLog.i("TILT default=${KsApp.settings.defaultTilt}") })
        Line {
            KButton(rs(R.string.coach_again), "coach_again", Modifier.weight(1f)) { KsApp.coach = 0; PanelNav.open(Page.COLORWAYS, "coach") }
            KButton(rs(R.string.reopen_panel), "reopen_panel", Modifier.weight(1f)) { KsLog.i("TAP reopen panel"); KsApp.reopenRequested = true }
        }
        Label(rs(R.string.reopen_hint), "settings/reopen_hint", secondary = true)
        if (!confirmReset) KButton(rs(R.string.reset), "reset", Modifier.fillMaxWidth()) { confirmReset = true }
        else Confirm(rs(R.string.reset_confirm), rs(R.string.reset_do), "reset", onCancel = { confirmReset = false }) {
            confirmReset = false; StudioStore.reset(); StudioStore.setPitch(KsApp.settings.defaultTilt); KsLog.i("RESET")
        }
        if (!confirmExit) KButton(rs(R.string.exit_app), "exit", Modifier.fillMaxWidth(), icon = R.drawable.ic_logout) { confirmExit = true; KsLog.i("TAP exit") }
        else Confirm(rs(R.string.exit_confirm), rs(R.string.exit_do), "exit", onCancel = { confirmExit = false }) { scope.launch { exitApp(context, navigator) } }
        SectionTitle(rs(R.string.about), "settings/about")
        Label(rs(R.string.about_text, io.embodit.keycapstudio.BuildConfig.VERSION_NAME), "settings/about_text", secondary = true)
    }
}

@Composable
fun VolumeRows() {
    val v = StudioStore.volume
    SliderRow(rs(R.string.volume), v * 100f, 0f..100f, rs(R.string.volume_pct, (v * 100).toInt()), "volume",
        onChange = { StudioStore.volume = it / 100f },
        onDone = { val snapped = listOf(35f, 65f, 100f).firstOrNull { kotlin.math.abs(it - StudioStore.volume * 100f) <= 6f }; if (snapped != null) StudioStore.volume = snapped / 100f })
    Toggle(StudioStore.muted, rs(R.string.act_mute), "mute") { StudioStore.muted = it }
}

/** Inline confirmation inside the same page (no second panel). */
@Composable
fun Confirm(question: String, confirm: String, name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(question, "confirm/$name", style = PicoTheme.typography.bodyLarge)
        Line {
            KButton(rs(R.string.cancel), "${name}_cancel", Modifier.weight(1f), onClick = onCancel)
            KButton(confirm, "${name}_confirm", Modifier.weight(1f), primary = true, onClick = onConfirm)
        }
    }
}

/**
 * D26 / D44 exit: save, release sound, close BOTH windows (panel + studio volume), remove every task, end the process.
 */
suspend fun exitApp(context: Context, navigator: SpatialNavigator) {
    KsLog.i("EXIT save + release + close panel + close studio + finishAndRemoveTask + exitProcess")
    KsApp.saveNow()
    runCatching { KsApp.sound.release() }
    runCatching { navigator.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { KsLog.w("EXIT close panel: $it") }
    delay(150)
    // the volume and the panel are separate tasks: close the volume with its own navigator (no-arg = itself)
    runCatching { (KsApp.navigator ?: navigator).let { if (it === navigator) it.closeWindowContainer(STUDIO_ID, "") else it.closeWindowContainer() } }
        .onFailure { KsLog.w("EXIT close studio: $it") }
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    repeat(2) { round ->
        delay(300)
        runCatching {
            val tasks = am.appTasks
            KsLog.i("EXIT round=$round tasks=${tasks.map { t -> runCatching { t.taskInfo.taskId }.getOrDefault(-1) }}")
            tasks.forEach { t -> runCatching { t.finishAndRemoveTask() } }
        }.onFailure { KsLog.e("EXIT finishAndRemoveTask failed", it) }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}
