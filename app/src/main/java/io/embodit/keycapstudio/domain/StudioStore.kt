package io.embodit.keycapstudio.domain

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Paint mode in single-key mode: colour the key, or move it in / out of the accent group (keysim's Accent). */
enum class SingleMode { PAINT, ACCENT }

/** A recolour the scene should animate: a colorway ripple from Esc, or a plain 0.12 s fade. */
data class RecolorEvent(val seq: Int, val ripple: Boolean)

/** Typing practice (design `content.tryTyping`). */
data class Typing(val text: String, val ids: List<String>, val index: Int = 0, val presses: Int = 0, val startMs: Long = 0L, val endMs: Long = 0L) {
    val done get() = index >= ids.size
    val nextId get() = ids.getOrNull(index)
}

/**
 * Process-level store shared by the studio volume and the panel window (native-window-move §3.4): the panel edits it,
 * the scene reads it. Compose snapshot state, so both containers recompose; the scene only reads it from its
 * snapshotFlow collectors, never per frame.
 */
object StudioStore {
    lateinit var full: Layout
    lateinit var tkl: Layout
    var colorways: List<Colorway> = emptyList()
    val ready get() = ::full.isInitialized

    var scheme by mutableStateOf(Scheme())
        private set
    var recolor by mutableStateOf(RecolorEvent(0, false))
        private set

    // ---- view state (not part of the scheme / undo)
    var detail by mutableStateOf(false)
    var target by mutableStateOf(PaletteTarget.MODS)
    var singleMode by mutableStateOf(SingleMode.PAINT)
    /** Current paint colour (palette wheel / HEX / swatches). */
    var paint by mutableIntStateOf(Hex.parse("#FB7279"))
    var typing by mutableStateOf<Typing?>(null)
    var panelState by mutableStateOf("never")
    var sceneReady by mutableStateOf(false)
    var keysBuilt by mutableIntStateOf(0)
    var muted by mutableStateOf(false)
    var volume by mutableFloatStateOf(1.0f)
    var lastPressed by mutableStateOf<String?>(null)
    /** Panel notices (snackbar-like line); [noticeError] picks the error role (S19). */
    var notice by mutableStateOf<String?>(null)
    var noticeError by mutableStateOf(false)
    var noticeSeq by mutableIntStateOf(0)

    private val undo = ArrayDeque<Scheme>()
    private val redo = ArrayDeque<Scheme>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** Bumped on every scheme change; the repository debounces autosave on it. */
    var revision by mutableIntStateOf(0)
        private set

    val layout: Layout get() = if (scheme.layout == 104) full else tkl
    val single get() = target == PaletteTarget.SINGLE

    fun init(full: Layout, tkl: Layout, colorways: List<Colorway>, restored: Scheme?) {
        this.full = full; this.tkl = tkl; this.colorways = colorways
        scheme = restored?.let { fillMissing(it) } ?: defaultScheme()
    }

    fun defaultScheme(): Scheme {
        val cw = colorways.first { it.id == "olivia" }
        return Scheme(colors = cw.colorsFor(full.keys.map { it.id }, Groups.ACCENT_DEFAULT), colorwayId = cw.id)
    }

    /** A scheme from an older file / import may miss keys: fill them from Olivia so every key has a colour. */
    private fun fillMissing(s: Scheme): Scheme {
        val missing = full.keys.map { it.id }.filter { it !in s.colors }
        if (missing.isEmpty()) return s
        val base = colorways.first().colorsFor(missing, s.accents)
        return s.copy(colors = s.colors + base)
    }

    /** Every user edit goes through here: one undo step (design `rules.undo`, 50 steps). */
    private fun commit(next: Scheme, ripple: Boolean = false, animate: Boolean = true) {
        if (next == scheme) return
        undo.addLast(scheme); if (undo.size > 50) undo.removeFirst()
        redo.clear()
        set(next, ripple, animate)
    }

    private fun set(next: Scheme, ripple: Boolean, animate: Boolean = true) {
        scheme = next
        if (animate) recolor = RecolorEvent(recolor.seq + 1, ripple)
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty(); revision++
    }

    fun undo() { val prev = undo.removeLastOrNull() ?: return; redo.addLast(scheme); set(prev, false) }
    fun redo() { val next = redo.removeLastOrNull() ?: return; undo.addLast(scheme); set(next, false) }

    // ---- colour edits
    fun applyColorway(id: String): Boolean {
        val cw = colorways.firstOrNull { it.id == id } ?: return false
        val s = scheme
        commit(s.copy(colors = cw.colorsFor(full.keys.map { it.id }, s.accents), colorwayId = cw.id, style = if (s.style == Style.SHINE || s.style == Style.SIDE) s.style else Style.SOLID, fill = Fill.SOLID), ripple = true)
        return true
    }

    private var lastRandom: String? = null
    fun randomColorway(): String {
        val pool = colorways.map { it.id }.filter { it != lastRandom && it != scheme.colorwayId }
        val pick = pool.random()
        lastRandom = pick
        applyColorway(pick)
        return pick
    }

    /** Palette: paint the current target (design `rules.paintGroup`). Legend stays unless the mode recomputes it. */
    fun paintTarget(color: Int = paint) {
        paint = color
        val s = scheme
        when (target) {
            PaletteTarget.SINGLE -> return
            PaletteTarget.CASE -> { commit(s.copy(caseColor = color)); return }
            else -> {}
        }
        val ids = target.keys(layout, s.accents)
        val next = s.colors.toMutableMap()
        ids.forEach { id -> next[id] = KeyColor(color, s.colors[id]?.fg ?: Hex.autoLegend(color)) }
        commit(s.copy(colors = next, colorwayId = null, style = if (s.style == Style.GRADIENT || s.style == Style.THEMED) Style.SOLID else s.style, fill = Fill.SOLID))
    }

    /** Single-key mode tap: paint the key or toggle its accent membership. Returns what happened for the log. */
    fun tapSingle(id: String): String {
        val s = scheme
        return when (singleMode) {
            SingleMode.PAINT -> {
                commit(s.copy(colors = s.colors + (id to KeyColor(paint, s.colors[id]?.fg ?: Hex.autoLegend(paint))), colorwayId = null, fill = Fill.SOLID,
                    style = if (s.style == Style.GRADIENT || s.style == Style.THEMED) Style.SOLID else s.style))
                "paint ${Hex.format(paint)}"
            }
            SingleMode.ACCENT -> {
                val acc = if (id in s.accents) s.accents - id else s.accents + id
                val accentColor = colorways.firstOrNull { it.id == s.colorwayId }?.swatches?.get("accent")
                    ?: s.colors[s.accents.firstOrNull() ?: "KC_ESC"]
                val c = if (id in acc && accentColor != null) s.colors + (id to accentColor) else s.colors
                commit(s.copy(accents = acc, colors = c))
                if (id in acc) "accent+" else "accent-"
            }
        }
    }

    fun setLegendMode(mode: LegendMode, custom: Int? = null) = commit(scheme.copy(legendMode = mode, customLegend = custom ?: scheme.customLegend))

    // ---- pattern / material
    fun setStyle(style: Style) {
        val s = scheme
        val fill = when (style) { Style.SOLID -> Fill.SOLID; Style.GRADIENT -> Fill.GRADIENT; Style.THEMED -> Fill.THEMED; else -> s.effectiveFill }
        commit(s.copy(style = style, fill = fill))
    }
    fun update(animate: Boolean = false, f: (Scheme) -> Scheme) = commit(f(scheme), animate = animate)

    fun setLayout(size: Int) { if (size != scheme.layout) commit(scheme.copy(layout = size), animate = false) }

    /** Pose edits from the rim drag are live (no undo step per frame): [commitPose] closes one step at drag end. */
    private var poseStart: Scheme? = null
    fun livePose(yaw: Float, pitch: Float) {
        if (poseStart == null) poseStart = scheme
        scheme = scheme.copy(yaw = yaw, pitch = pitch.coerceIn(0f, KeyboardPose.MAX_PITCH)); revision++
    }
    fun commitPose() {
        val start = poseStart ?: return
        poseStart = null
        if (start != scheme) { undo.addLast(start); if (undo.size > 50) undo.removeFirst(); redo.clear(); canUndo = true; canRedo = false }
    }
    fun rotate90() { if (!detail) commit(scheme.copy(yaw = ((scheme.yaw + 90f) % 360f)), animate = false) }
    fun setPitch(p: Float) = commit(scheme.copy(pitch = p.coerceIn(0f, KeyboardPose.MAX_PITCH)), animate = false)

    /** Load a saved / imported scheme (one undo step, ripple like a colorway). */
    fun load(s: Scheme) = commit(fillMissing(s), ripple = true)
    fun rename(name: String) { scheme = scheme.copy(name = name); revision++ }
    fun reset() { undo.clear(); redo.clear(); detail = false; target = PaletteTarget.MODS; set(defaultScheme(), true) }

    fun notify(text: String, error: Boolean = false) { notice = text; noticeError = error; noticeSeq++ }
}
