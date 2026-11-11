package io.embodit.keycapstudio.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.embodit.keycapstudio.KsApp
import io.embodit.keycapstudio.R
import io.embodit.keycapstudio.domain.CapMaterial
import io.embodit.keycapstudio.domain.Lang
import io.embodit.keycapstudio.domain.PaletteTarget
import io.embodit.keycapstudio.domain.Profile
import io.embodit.keycapstudio.domain.SingleMode
import io.embodit.keycapstudio.domain.StudioStore
import io.embodit.keycapstudio.domain.Style
import io.embodit.keycapstudio.domain.SwitchType
import io.embodit.keycapstudio.domain.Hex
import io.embodit.keycapstudio.platform.KsLog
import io.embodit.keycapstudio.ui.Page
import io.embodit.keycapstudio.ui.PanelFlags
import io.embodit.keycapstudio.ui.PanelNav
import io.embodit.keycapstudio.ui.SENTENCES
import io.embodit.keycapstudio.ui.SchemeActions
import io.embodit.keycapstudio.ui.StudioInput
import io.embodit.keycapstudio.ui.localizedResources
import io.embodit.keycapstudio.ui.startTyping
import kotlinx.coroutines.launch

/**
 * Debug builds only (design `qaHooks`). Every command goes through the same store / controller entry as the real
 * buttons and pointer (build.md 2026-10-29: no shortcuts that set state directly).
 *
 *   adb -s emulator-5556 shell "am broadcast -a io.embodit.keycapstudio.DEBUG -p io.embodit.keycapstudio --es cmd 'colorway laser'"
 *
 * Commands (several separated by `;`): state S01..S19 · page colorways|palette|patterns|material|schemes|settings ·
 * tap <keyId> · press <keyId> [hold] · release <keyId> · colorway <id> · random · layout 87|104 ·
 * pose <yaw> <pitch> · detail on|off · rotate · undo · redo · target <alphas|mods|…|single> · paint <#hex> ·
 * single paint|accent · pattern solid|gradient|themed|side|shine · theme <id> · grad <0-3> · material pbt|abs|pc ·
 * profile oem|cherry · case <#hex> · switch linear|tactile|clicky|topre|silent · listen · volume <0-100> · mute on|off ·
 * lang zh|en|system · save [name] · saveas [name] · load <index> · delete <index> · export card|json · import json|image ·
 * typing <0-2>|stop · coach <0-2>|off · reopen · close · confirm exit|reset|delete|off · notice <text> · error <text> · reset · dump
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Handler(Looper.getMainLooper()).post { cmd.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { run(context.applicationContext, it) } }
    }

    private fun res(context: Context) = localizedResources(context, KsApp.settings.lang)

    private fun run(context: Context, line: String) {
        val a = line.split(Regex("\\s+"))
        val arg = a.getOrNull(1)
        KsLog.i("CMD $line")
        val scene = KsApp.scene?.get()
        runCatching {
            when (a[0]) {
                "state" -> state(context, arg ?: "S02")
                "page" -> PanelNav.open(Page.valueOf(arg!!.uppercase()), "debug")
                "tap" -> StudioInput.onKeyTap(arg!!)
                "press" -> scene?.pressKey(arg!!, hold = a.getOrNull(2) == "hold")
                "release" -> scene?.releaseKey(arg!!)
                "colorway" -> StudioStore.applyColorway(arg!!)
                "random" -> StudioStore.randomColorway()
                "layout" -> StudioStore.setLayout(arg!!.toInt())
                "pose" -> { StudioStore.livePose(arg!!.toFloat(), a[2].toFloat()); StudioStore.commitPose() }
                "detail" -> StudioStore.detail = arg == "on"
                "rotate" -> StudioStore.rotate90()
                "undo" -> StudioStore.undo()
                "redo" -> StudioStore.redo()
                "target" -> StudioStore.target = PaletteTarget.valueOf(arg!!.uppercase())
                "paint" -> { val c = Hex.parse(arg!!); StudioStore.paint = c; if (!StudioStore.single) StudioStore.paintTarget(c) }
                "single" -> { StudioStore.target = PaletteTarget.SINGLE; StudioStore.singleMode = if (arg == "accent") SingleMode.ACCENT else SingleMode.PAINT }
                "pattern" -> StudioStore.setStyle(Style.valueOf(arg!!.uppercase()))
                "theme" -> StudioStore.update { it.copy(theme = arg!!) }
                "grad" -> StudioStore.update { it.copy(gradientDir = arg!!.toInt()) }
                "backlight" -> StudioStore.update { it.copy(backlight = Hex.parse(arg!!)) }
                "material" -> StudioStore.update { it.copy(material = CapMaterial.valueOf(arg!!.uppercase())) }
                "profile" -> StudioStore.update { it.copy(profile = Profile.valueOf(arg!!.uppercase())) }
                "case" -> StudioStore.update { it.copy(caseColor = Hex.parse(arg!!)) }
                "switch" -> StudioStore.update { it.copy(switch = SwitchType.valueOf(arg!!.uppercase())) }
                "listen" -> listOf("KC_F", "KC_J", "KC_SPC").forEachIndexed { i, id -> Handler(Looper.getMainLooper()).postDelayed({ scene?.pressKey(id) }, i * 300L) }
                "volume" -> StudioStore.volume = arg!!.toFloat() / 100f
                "mute" -> StudioStore.muted = arg == "on"
                "lang" -> KsApp.updateSettings { it.copy(lang = Lang.valueOf(arg!!.uppercase())) }
                "save" -> { arg?.let { StudioStore.rename(a.drop(1).joinToString(" ")) }; SchemeActions.save(context, res(context), asNew = false) }
                "saveas" -> { arg?.let { StudioStore.rename(a.drop(1).joinToString(" ")) }; SchemeActions.save(context, res(context), asNew = true) }
                "load" -> SchemeActions.load(res(context), KsApp.repo.list()[arg!!.toInt()])
                "delete" -> { KsApp.repo.delete(KsApp.repo.list()[arg!!.toInt()].id); SchemeActions.listSeq++ }
                "export" -> if (arg == "json") SchemeActions.exportJson(res(context)) else SchemeActions.exportCard(context, res(context))
                "import" -> if (arg == "image") SchemeActions.importImage(res(context)) else SchemeActions.importJson(res(context))
                "typing" -> if (arg == "stop") StudioStore.typing = null else startTyping(res(context).getString(SENTENCES[arg?.toInt() ?: 1]))
                "coach" -> KsApp.coach = if (arg == "off") -1 else arg!!.toInt()
                "reopen" -> KsApp.reopenRequested = true
                "close" -> KsApp.closeRequested = true
                "confirm" -> { PanelFlags.confirmExit.value = arg == "exit"; PanelFlags.confirmReset.value = arg == "reset"
                    PanelFlags.confirmDelete.value = if (arg == "delete") KsApp.repo.list().firstOrNull()?.id else null }
                "notice" -> StudioStore.notify(a.drop(1).joinToString(" "))
                "error" -> StudioStore.notify(a.drop(1).joinToString(" ").ifBlank { res(context).getString(R.string.json_bad) }, error = true)
                "reset" -> StudioStore.reset()
                "dump" -> dump()
                "exit" -> KsApp.navigator?.let { nav -> KsApp.scope.launch { io.embodit.keycapstudio.ui.exitApp(context, nav) } }
                "lift" -> { io.embodit.keycapstudio.domain.CapShape.LEGEND_LIFT = arg!!.toFloat() / 1000f; scene?.rebuildNow() }
                "legblend" -> scene?.legendBlend(com.pico.spatial.core.ecs.resource.BlendingMode.valueOf(arg!!.uppercase()))
                "flipv" -> { io.embodit.keycapstudio.scene.KeyboardScene.flipV = arg == "on"; scene?.rebuildNow() }
                else -> KsLog.w("CMD unknown: $line")
            }
        }.onFailure { KsLog.e("CMD failed: $line", it) }
    }

    /** design `states[]`: each id reachable in one command (QA captures by name). */
    private fun state(context: Context, id: String) {
        val r = res(context)
        StudioStore.detail = false; StudioStore.typing = null
        PanelFlags.confirmExit.value = false; PanelFlags.confirmReset.value = false; PanelFlags.confirmDelete.value = null
        if (StudioStore.target == PaletteTarget.SINGLE && id != "S05") StudioStore.target = PaletteTarget.MODS
        when (id.uppercase()) {
            "S01", "S02" -> PanelNav.open(Page.COLORWAYS, "state")
            "S03" -> PanelNav.open(Page.COLORWAYS, "state")
            "S04" -> { PanelNav.open(Page.PALETTE, "state"); StudioStore.target = PaletteTarget.MODS }
            "S05" -> { PanelNav.open(Page.PALETTE, "state"); StudioStore.target = PaletteTarget.SINGLE; StudioStore.singleMode = SingleMode.PAINT }
            "S06" -> PanelNav.open(Page.PATTERNS, "state")
            "S07" -> PanelNav.open(Page.MATERIAL, "state")
            "S08" -> { PanelNav.open(Page.COLORWAYS, "state"); StudioStore.setLayout(if (StudioStore.scheme.layout == 87) 104 else 87) }
            "S09" -> KsApp.scene?.get()?.pressKey("KC_J", hold = true)
            "S10" -> StudioStore.detail = true
            "S11" -> { PanelNav.open(Page.MATERIAL, "state"); startTyping(r.getString(SENTENCES[1])) }
            "S12" -> PanelNav.open(Page.SCHEMES, "state")
            "S13" -> { PanelNav.open(Page.SCHEMES, "state"); SchemeActions.exportCard(context, r) }
            "S14" -> { PanelNav.open(Page.PATTERNS, "state"); StudioStore.setStyle(Style.THEMED); SchemeActions.importImage(r) }
            "S15" -> PanelNav.open(Page.SETTINGS, "state")
            "S16" -> { PanelNav.open(Page.SETTINGS, "state"); PanelFlags.confirmExit.value = true }
            "S17" -> { KsApp.reopenRequested = false; KsApp.closeRequested = true }
            "S18" -> { KsApp.coach = 0; PanelNav.open(Page.COLORWAYS, "state") }
            "S19" -> StudioStore.notify(r.getString(R.string.json_bad), error = true)
            else -> KsLog.w("STATE unknown $id")
        }
        KsLog.i("STATE $id page=${PanelNav.page}")
    }

    private fun dump() {
        val s = StudioStore.scheme
        val scene = KsApp.scene?.get()
        KsLog.i("DUMP layout=${s.layout} colorway=${s.colorwayId} style=${s.style} fill=${s.effectiveFill} material=${s.effectiveMaterial} profile=${s.profile} switch=${s.switch} " +
            "yaw=${s.yaw} pitch=${s.pitch} detail=${StudioStore.detail} pose=${scene?.pose} target=${StudioStore.target} paint=${Hex.format(StudioStore.paint)} " +
            "esc=${s.colors["KC_ESC"]?.let { Hex.format(it.bg) }} lctl=${s.colors["KC_LCTL"]?.let { Hex.format(it.bg) }} a=${s.colors["KC_A"]?.let { Hex.format(it.bg) }} " +
            "panel=${StudioStore.panelState} page=${PanelNav.page} undo=${StudioStore.canUndo} saved=${KsApp.repo.list().size} lang=${KsApp.settings.lang} muted=${StudioStore.muted} vol=${StudioStore.volume}")
    }
}
