package io.embodit.keycapstudio.scene

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.keycapstudio.domain.CapMaterial
import io.embodit.keycapstudio.domain.CapProfiles
import io.embodit.keycapstudio.domain.CapShape
import io.embodit.keycapstudio.domain.Fill
import io.embodit.keycapstudio.domain.Hex
import io.embodit.keycapstudio.domain.Key
import io.embodit.keycapstudio.domain.KeyboardPose
import io.embodit.keycapstudio.domain.Layout
import io.embodit.keycapstudio.domain.MeshData
import io.embodit.keycapstudio.domain.Profile
import io.embodit.keycapstudio.domain.Scheme
import io.embodit.keycapstudio.domain.StudioStore
import io.embodit.keycapstudio.domain.Style
import io.embodit.keycapstudio.domain.V3
import io.embodit.keycapstudio.platform.KsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What a pointer hit resolves to (entity names `key:<id>` / `rim`). */
sealed interface Hit {
    data class KeyHit(val id: String) : Hit
    data object Rim : Hit
}

/**
 * The 1:1 keyboard inside the studio volume (design `entities3d`). All high-frequency work (press travel, colour
 * fades, ripple, breathing, pose easing, layout slide) runs in one frame loop here; Compose only feeds the store.
 */
class KeyboardScene(private val context: Context, private val sound: SoundBank) {
    val root = Entity().apply { setName("studioRoot") }
    private val pivot = Entity().apply { setName("pivot") }
    private val caseNode = Entity().apply { setName("caseRoot") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owned = ArrayList<Resource>()
    private val importDir = File(context.filesDir, "import")

    private class KeyNode(val key: Key, val index: Int, val root: Entity, val cap: Entity, val legend: Entity,
                          val capMat: PhysicallyBasedMaterial, val legendMat: PhysicallyBasedMaterial) {
        var shown = Hex.parse("#808080"); var from = shown; var to = shown
        var fgShown = shown; var fgFrom = shown; var fgTo = shown
        var fadeStart = 0L; var fadeDelay = 0L; var fading = false
        var pressAt = 0L; var holding = false; var releaseAt = 0L; var released = true; var pressed = false; var sounded = false
        var x = 0f; var z = 0f; var xFrom = 0f; var xTo = 0f
        /** Which cached meshes the entities show now (profile; profile + side print). */
        var capProfile: Profile? = null; var legendVariant: Pair<Profile, Boolean>? = null
    }

    // mesh caches (q1-3): built once per profile / legend variant, switching back is a plain swap
    private val capCache = HashMap<Profile, HashMap<String, MeshResource>>()
    private val legendCache = HashMap<Pair<Profile, Boolean>, HashMap<String, MeshResource>>()
    private var rebuildJob: Job? = null

    private val nodes = LinkedHashMap<String, KeyNode>()
    private lateinit var caseModel: Entity
    private lateinit var caseMat: PhysicallyBasedMaterial
    private val caseMeshes = HashMap<Int, MeshResource>()
    private lateinit var plate: Entity
    private val plateMeshes = HashMap<Int, MeshResource>()
    private lateinit var rim: Entity
    private var atlasTop: TextureResource? = null
    private var atlasSide: TextureResource? = null
    private var boardTex: TextureResource? = null
    private lateinit var white: TextureResource
    private lateinit var flatNormal: TextureResource
    private lateinit var grain: TextureResource
    private var applied: Scheme? = null
    private var lastRecolorSeq = -1
    private var built = false

    // pose easing
    private var yaw = 0f; private var pitch = KeyboardPose.DEFAULT_PITCH; private var scale = 1f
    private var poseAnimFrom = floatArrayOf(0f, 0f, 1f); private var poseAnimTo = floatArrayOf(0f, 0f, 1f); private var poseAnimStart = 0L; private var poseAnimating = false
    private var layoutAnimStart = 0L; private var layoutAnimating = false; private var layoutTo = 87
    private var dragYaw = 0f; private var dragPitch = 0f
    var onPoseChanged: (() -> Unit)? = null

    private fun <T : Resource> keep(r: T): T { r.toGlobal(); owned += r; return r }

    // ---------------------------------------------------------------------------------- build
    suspend fun build() {
        val store = StudioStore
        root.components.get<TransformComponent>()?.setPosition(Vector3(0f, -KeyboardPose.VOL_H / 2f, 0f))
        root.addChild(pivot); pivot.addChild(caseNode)
        caseNode.components.get<TransformComponent>()?.setPosition(Vector3(0f, KeyboardPose.FOOT_H, -KeyboardPose.CASE_D / 2f))
        light()
        white = keep(TextureResource.create(solid(0xFFFFFFFF.toInt())))
        flatNormal = keep(TextureResource.create(solid(0xFF8080FF.toInt())))
        grain = keep(TextureResource.create(Art.grainNormal()))
        caseMat = keep(PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply { setMetallic(0.55f); setRoughness(0.42f); setCullingMode(MaterialCullingMode.NONE) })
        val plateMat = keep(PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(0.07f, 0.07f, 0.08f, 1f)); setRoughness(0.8f); setMetallic(0.2f) })
        for (size in listOf(87, 104)) {
            caseMeshes[size] = keep(mesh(CaseMesh.case(KeyboardPose.caseW(size)), "case$size"))
            plateMeshes[size] = keep(mesh(CaseMesh.plate(KeyboardPose.caseW(size)), "plate$size"))
        }
        val s = store.scheme
        caseModel = ModelEntity(caseMeshes.getValue(s.layout), caseMat).apply { setName("case") }
        plate = ModelEntity(plateMeshes.getValue(s.layout), plateMat).apply { setName("plate") }
        caseNode.addChild(caseModel); caseNode.addChild(plate)
        rim = Entity().apply { setName("rim") }
        caseNode.addChild(rim)
        setRim(s.layout)
        yaw = s.yaw; pitch = s.pitch; applyPose()
        atlasTop = withContext(Dispatchers.Default) { Art.legendAtlas(context, store.full.keys, side = false) }.let { b -> keep(TextureResource.create(b)) }  // no recycle: the upload may read the bitmap later
        // keys: caps grow in batches of 16 per frame (design `rules.coldStart`, A9)
        val keys = store.full.keys
        keys.chunked(BATCH).forEach { batch ->
            val caps = capCache.getOrPut(s.profile) { HashMap() }
            val legends = legendCache.getOrPut(s.profile to (s.style == Style.SIDE)) { HashMap() }
            batch.forEach { k ->
                ensure(caps, k.id) { mesh(capData(k, s), "cap_${k.id}_${s.profile}") }
                ensure(legends, k.id) { mesh(legendData(k, keys.indexOf(k), s), "leg_${k.id}_${s.profile}_${s.style == Style.SIDE}") }
                addKey(k, keys.indexOf(k), caps.getValue(k.id), legends.getValue(k.id), s)
            }
            store.keysBuilt = nodes.size
            delay(16)
        }
        built = true
        applied = null
        applyScheme(s, first = true)
        store.sceneReady = true
        KsLog.i("SCENE_READY keys=${nodes.size} layout=${s.layout} profile=${s.profile}")
        scope.launch { snapshotFlow { StudioStore.scheme }.collect { if (built) applyScheme(it) } }
        scope.launch { snapshotFlow { StudioStore.detail }.collect { setDetail(it) } }
        scope.launch { frameLoop() }
        prewarm()
    }

    private fun light() {
        // a soft 45° key light from the front-top so ABS gloss shows a highlight (design `materials.lighting`)
        val l = Entity().apply { setName("keyLight") }
        l.components.set(DirectionalLightComponent(Color4(1f, 0.97f, 0.92f, 1f), 2500f, false))
        l.components.get<TransformComponent>()?.setQuaternion(axisAngle(1f, 0f, 0f, -45f) * axisAngle(0f, 1f, 0f, 20f))
        root.addChild(l)
    }

    private fun solid(argb: Int): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private fun setRim(layout: Int) {
        // the rim: the case body plus a 1 cm strip under the front edge; drag it to turn / tilt (not a grab bar, D21)
        val w = KeyboardPose.caseW(layout)
        val body = ShapeResource.createBox(Vector3(w, KeyboardPose.CASE_H, KeyboardPose.CASE_D)).offsetByTranslation(Vector3(0f, KeyboardPose.CASE_H / 2f, 0f))
        val strip = ShapeResource.createBox(Vector3(w, 0.01f, 0.02f)).offsetByTranslation(Vector3(0f, 0.005f - KeyboardPose.FOOT_H, KeyboardPose.CASE_D / 2f + 0.008f))
        rim.components.set(CollisionComponent(listOf(body, strip), PhysicsMaterialResource()))
        rim.components.set(InteractableComponent())
    }

    private fun uvFull(k: Key): (Float, Float) -> Pair<Float, Float> {
        val full = StudioStore.full
        val bw = full.widthU * Layout.U_MM / 1000f; val bd = full.heightU * Layout.U_MM / 1000f
        val kx = k.cx * Layout.U_MM / 1000f; val kz = k.cy * Layout.U_MM / 1000f
        return { lx, lz -> ((kx + lx) / bw) to ((kz + lz) / bd) }
    }

    private fun shapeOf(k: Key, s: Scheme) = CapShape(CapProfiles.of(s.profile), k.row, k.w, k.h)
    private fun capData(k: Key, s: Scheme): MeshData = shapeOf(k, s).build(uvOf = uvFull(k))
    private fun legendData(k: Key, i: Int, s: Scheme): MeshData {
        val c = Art.cellUv(i)
        val sh = shapeOf(k, s)
        // atlas uploads flipped (Art.flip): cell rows count from the bottom of the texture
        val v0 = if (flipV) 1f - c[1] else c[1]; val v1 = if (flipV) 1f - c[3] else c[3]
        return if (s.style == Style.SIDE) sh.legendFront(c[0], v0, c[2], v1) else sh.legendTop(minOf(sh.topW, sh.topD) * 0.92f, c[0], v0, c[2], v1)
    }

    private fun mesh(d: MeshData, name: String): MeshResource {
        var mn = V3(1e9f, 1e9f, 1e9f); var mx = V3(-1e9f, -1e9f, -1e9f)
        d.pos.forEach { mn = V3(minOf(mn.x, it.x), minOf(mn.y, it.y), minOf(mn.z, it.z)); mx = V3(maxOf(mx.x, it.x), maxOf(mx.y, it.y), maxOf(mx.z, it.z)) }
        val c = (mn + mx) * 0.5f; val h = (mx - mn) * 0.5f
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = d.pos.map { Vector3(it.x, it.y, it.z) }, triangleIndices = d.idx, normals = d.nrm.map { Vector3(it.x, it.y, it.z) }, uv0 = d.uv.map { Vector2(it.first, it.second) }),
            bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(h.x, 0.0005f), maxOf(h.y, 0.0005f), maxOf(h.z, 0.0005f))),
            name = name,
        )
    }

    private fun addKey(k: Key, i: Int, cap: MeshResource, legend: MeshResource, s: Scheme) {
        val capMat = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply { setMetallic(0f); setCullingMode(MaterialCullingMode.NONE) }
        val legMat = PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
            setDepthWrite(false); setBaseColorTexture(atlasTop!!); setMetallic(0f); setRoughness(0.6f); setCullingMode(MaterialCullingMode.NONE)
        }
        val capE = ModelEntity(cap, capMat).apply { setName("cap:${k.id}") }
        val legE = ModelEntity(legend, legMat).apply { setName("legend:${k.id}") }
        val r = Entity().apply { setName("key:${k.id}") }
        val sh = shapeOf(k, s)
        val box = ShapeResource.createBox(Vector3(sh.bottomW + 0.001f, sh.height + 0.002f, sh.bottomD + 0.001f)).offsetByTranslation(Vector3(0f, sh.height / 2f, 0f))
        r.components.set(CollisionComponent(listOf(box), PhysicsMaterialResource()))
        r.components.set(InteractableComponent())
        r.components.set(HoverEffectComponent())
        r.addChild(capE); r.addChild(legE)
        caseNode.addChild(r)
        val n = KeyNode(k, i, r, capE, legE, capMat, legMat)
        n.capProfile = s.profile; n.legendVariant = s.profile to (s.style == Style.SIDE)
        n.x = keyX(k, s.layout); n.xTo = n.x; n.z = keyZ(k)
        place(n, 0f)
        r.enabled = !k.numpad || s.layout == 104
        nodes[k.id] = n
    }

    private fun keyX(k: Key, layout: Int): Float { val lay = if (layout == 104) StudioStore.full else StudioStore.tkl; return (k.cx - lay.widthU / 2f) * Layout.U_MM / 1000f }
    private fun keyZ(k: Key): Float = (k.cy - StudioStore.full.heightU / 2f) * Layout.U_MM / 1000f

    private fun place(n: KeyNode, travel: Float) {
        n.root.components.get<TransformComponent>()?.setPosition(Vector3(n.x, KeyboardPose.CAP_BOTTOM_Y - travel, n.z))
    }

    // ---------------------------------------------------------------------------------- scheme → scene
    private suspend fun applyScheme(s: Scheme, first: Boolean = false) {
        val prev = applied
        applied = s
        if (prev == null || prev.layout != s.layout) startLayout(s.layout, animate = prev != null)
        if (prev != null && (prev.profile != s.profile || (prev.style == Style.SIDE) != (s.style == Style.SIDE))) startRebuild(s)
        if (prev == null || prev.caseColor != s.caseColor) caseMat.setBaseColor(c4(s.caseColor))
        val fillChanged = prev == null || prev.effectiveFill != s.effectiveFill || prev.gradientDir != s.gradientDir || prev.gradientA != s.gradientA ||
            prev.gradientB != s.gradientB || prev.theme != s.theme || prev.themeA != s.themeA || prev.themeB != s.themeB || prev.image != s.image ||
            (prev.layout != s.layout && s.effectiveFill != Fill.SOLID)
        if (fillChanged) applyFill(s)
        if (prev == null || prev.effectiveMaterial != s.effectiveMaterial) applyMaterial(s)
        val recolor = StudioStore.recolor
        val colorsChanged = prev == null || prev.colors != s.colors || prev.legendMode != s.legendMode || prev.customLegend != s.customLegend
        if (colorsChanged) startRecolor(s, ripple = !first && recolor.ripple && recolor.seq != lastRecolorSeq, instant = first)
        lastRecolorSeq = recolor.seq
        if (prev == null || prev.style != s.style || prev.backlight != s.backlight || prev.brightness != s.brightness || prev.breathe != s.breathe) applyShine(s)
        if (!poseAnimating && !StudioStore.detail) { yaw = s.yaw; pitch = s.pitch; applyPose() }
    }

    private fun applyFill(s: Scheme) {
        val old = boardTex
        if (s.effectiveFill == Fill.SOLID) {
            boardTex = null
            nodes.values.forEach { it.capMat.setBaseColorTexture(white); it.capMat.setBaseColor(c4(it.shown)) }
        } else {
            val bmp = Art.boardTexture(s, StudioStore.full, importDir)
            boardTex = TextureResource.create(bmp).also { it.toGlobal() }
            nodes.values.forEach { it.capMat.setBaseColorTexture(boardTex!!); it.capMat.setBaseColor(Color4(1f, 1f, 1f, 1f)) }
        }
        if (old != null) runCatching { old.close() }
        KsLog.i("SCENE fill=${s.effectiveFill} theme=${s.theme}")
    }

    private fun applyMaterial(s: Scheme) {
        val (rough, normal) = when (s.effectiveMaterial) {
            CapMaterial.PBT -> 0.75f to grain
            CapMaterial.ABS -> 0.22f to flatNormal
            CapMaterial.PC -> 0.35f to flatNormal
        }
        nodes.values.forEach { n -> n.capMat.setRoughness(rough); n.capMat.setNormalTexture(normal); n.capMat.setNormalScale(if (normal === grain) 0.35f else 0f) }
        KsLog.i("SCENE material=${s.effectiveMaterial} roughness=$rough")
    }

    private fun applyShine(s: Scheme) {
        val on = s.style == Style.SHINE
        nodes.values.forEach { n ->
            if (on) { n.legendMat.setEmissiveTexture(atlasFor(s)); n.legendMat.setEmissiveColor(scaled(s.backlight, s.brightness)) }
            else n.legendMat.setEmissiveColor(Color4(0f, 0f, 0f, 1f))
        }
    }

    private fun atlasFor(s: Scheme): TextureResource = if (s.style == Style.SIDE) atlasSide ?: atlasTop!! else atlasTop!!

    /**
     * Profile / side-print switch (QA #1 q1-3): a cancellable background job swaps each key to the cached meshes of the
     * target variant, making missing ones on [Dispatchers.Default] and attaching them a few keys per frame inside an
     * ~8 ms main-thread budget, so the panel keeps answering. A newer switch cancels the job and starts from where the
     * keys are; every key records which variant it shows.
     */
    private fun startRebuild(s: Scheme) {
        rebuildJob?.cancel(); prewarmJob?.cancel()
        rebuildJob = scope.launch { rebuildMeshes(s) }
    }

    private suspend fun rebuildMeshes(s: Scheme) {
        val t0 = SystemClock.uptimeMillis()
        val side = s.style == Style.SIDE
        if (side && atlasSide == null)
            atlasSide = withContext(Dispatchers.Default) { Art.legendAtlas(context, StudioStore.full.keys, side = true) }.let { b -> keep(TextureResource.create(b)) }  // no recycle: the upload may read the bitmap later
        val atlas = atlasFor(s)
        val keys = StudioStore.full.keys
        val caps = capCache.getOrPut(s.profile) { HashMap() }
        val legends = legendCache.getOrPut(s.profile to side) { HashMap() }
        var slice = SystemClock.uptimeMillis(); var worst = 0L; var swapped = 0; var made = 0
        for (k in keys) {
            val n = nodes[k.id] ?: continue
            // geometry and MeshResource for a key not cached yet are made off the main thread (~0.5–1 s each on the emulator)
            if (n.capProfile != s.profile && caps[k.id] == null) { ensure(caps, k.id) { mesh(capData(k, s), "cap_${k.id}_${s.profile}") }; made++ }
            if (n.legendVariant != (s.profile to side) && legends[k.id] == null) { ensure(legends, k.id) { mesh(legendData(k, keys.indexOf(k), s), "leg_${k.id}_${s.profile}_$side") }; made++ }
            val a = SystemClock.uptimeMillis()
            if (n.capProfile != s.profile) {
                val cap = caps.getValue(k.id)
                n.cap.components.get<ModelComponent>()?.mesh = cap
                val sh = shapeOf(k, s)
                n.root.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(sh.bottomW + 0.001f, sh.height + 0.002f, sh.bottomD + 0.001f)).offsetByTranslation(Vector3(0f, sh.height / 2f, 0f))), PhysicsMaterialResource()))
                n.capProfile = s.profile
            }
            if (n.legendVariant != (s.profile to side)) {
                val leg = legends.getValue(k.id)
                n.legend.components.get<ModelComponent>()?.mesh = leg
                n.legendMat.setBaseColorTexture(atlas)
                if (s.style == Style.SHINE) n.legendMat.setEmissiveTexture(atlas)
                n.legendVariant = s.profile to side
                swapped++
            }
            val now = SystemClock.uptimeMillis()
            worst = maxOf(worst, now - a)
            if (now - slice >= SLICE_MS) { delay(16); slice = SystemClock.uptimeMillis() }
        }
        KsLog.i("SCENE rebuilt profile=${s.profile} side=$side keys=$swapped made=$made ms=${SystemClock.uptimeMillis() - t0} worstKeyMs=$worst")
    }

    /** Make one cached mesh on [Dispatchers.Default]; the cache itself is only touched on the main thread. */
    private suspend fun ensure(cache: HashMap<String, MeshResource>, id: String, make: () -> MeshResource) {
        if (cache[id] != null) return
        val m = withContext(Dispatchers.Default) { make() }
        if (cache[id] == null) cache[id] = keep(m) else runCatching { m.close() }
    }

    /**
     * After SCENE_READY: make the other side-print legend set for the current profile in the background, one key at a
     * time, so the first Side print switch is a plain swap (q1-3). A rebuild in progress has priority (it cancels this).
     */
    private fun prewarm() {
        val s = StudioStore.scheme
        val side = s.style != Style.SIDE
        val keys = StudioStore.full.keys
        prewarmJob = scope.launch {
            val t0 = SystemClock.uptimeMillis()
            if (side && atlasSide == null)
                atlasSide = withContext(Dispatchers.Default) { Art.legendAtlas(context, keys, side = true) }.let { b -> keep(TextureResource.create(b)) }
            val legends = legendCache.getOrPut(s.profile to side) { HashMap() }
            val probe = s.copy(style = if (side) Style.SIDE else Style.SOLID)
            for (k in keys) { ensure(legends, k.id) { mesh(legendData(k, keys.indexOf(k), probe), "leg_${k.id}_${s.profile}_$side") }; delay(16) }
            KsLog.i("SCENE prewarm profile=${s.profile} side=$side ms=${SystemClock.uptimeMillis() - t0}")
        }
    }
    private var prewarmJob: Job? = null

    /** design `rules.applyColorway`: delay = distance from Esc / 0.7 m/s, 0.12 s fade; plain edits fade at once. */
    private fun startRecolor(s: Scheme, ripple: Boolean, instant: Boolean) {
        val now = SystemClock.uptimeMillis()
        val esc = nodes["KC_ESC"]
        nodes.values.forEach { n ->
            val c = s.colors[n.key.id] ?: return@forEach
            val fg = s.legendOf(n.key.id)
            if (instant) { n.shown = c.bg; n.fgShown = fg; n.fading = false; paint(n, s); return@forEach }
            n.from = n.shown; n.to = c.bg; n.fgFrom = n.fgShown; n.fgTo = fg
            n.fadeStart = now
            n.fadeDelay = if (ripple && esc != null) {
                val dx = (n.key.cx - esc.key.cx) * Layout.U_MM / 1000f; val dz = (n.key.cy - esc.key.cy) * Layout.U_MM / 1000f
                (sqrt(dx * dx + dz * dz) / 0.7f * 1000f).toLong()
            } else 0L
            n.fading = n.from != n.to || n.fgFrom != n.fgTo
        }
        if (ripple) sound.swoosh()
        KsLog.i("SCENE recolor ripple=$ripple colorway=${s.colorwayId}")
    }

    private fun paint(n: KeyNode, s: Scheme) {
        if (s.effectiveFill == Fill.SOLID) n.capMat.setBaseColor(c4(n.shown))
        n.legendMat.setBaseColor(c4(n.fgShown))
    }

    private fun startLayout(size: Int, animate: Boolean) {
        layoutTo = size
        nodes.values.forEach { n -> n.xFrom = n.x; n.xTo = keyX(n.key, size); if (n.key.numpad && size == 104) n.root.enabled = true }
        caseModel.components.get<ModelComponent>()?.mesh = caseMeshes.getValue(size)
        plate.components.get<ModelComponent>()?.mesh = plateMeshes.getValue(size)
        setRim(size)
        if (animate) { layoutAnimStart = SystemClock.uptimeMillis(); layoutAnimating = true; sound.slide() }
        else { nodes.values.forEach { n -> n.x = n.xTo; place(n, 0f); n.root.enabled = !n.key.numpad || size == 104 } }
        onPoseChanged?.invoke()
        KsLog.i("SCENE layout=$size animate=$animate")
    }

    // ---------------------------------------------------------------------------------- pose
    private fun applyPose() {
        val t = pivot.components.get<TransformComponent>() ?: return
        t.setPosition(Vector3(0f, 0f, KeyboardPose.CASE_D * scale / 2f))
        t.setQuaternion(axisAngle(0f, 1f, 0f, yaw) * axisAngle(1f, 0f, 0f, pitch))
        t.setScaleVector(Vector3(scale, scale, scale))
        onPoseChanged?.invoke()
    }

    val pose get() = Triple(yaw, pitch, scale)

    private var savedYaw = 0f
    private fun setDetail(on: Boolean) {
        val s = StudioStore.scheme
        poseAnimFrom = floatArrayOf(yaw, pitch, scale)
        poseAnimTo = if (on) { savedYaw = s.yaw; floatArrayOf(nearestZero(yaw), KeyboardPose.DETAIL_PITCH, KeyboardPose.DETAIL_SCALE) } else floatArrayOf(s.yaw, s.pitch, 1f)
        poseAnimStart = SystemClock.uptimeMillis(); poseAnimating = true
        KsLog.i("SCENE detail=$on")
    }

    private fun nearestZero(y: Float): Float = Math.round(y / 360f) * 360f

    fun dragStart() { dragYaw = StudioStore.scheme.yaw; dragPitch = StudioStore.scheme.pitch }

    /** Rim drag: left / right turns (1×, any yaw, stops where released), up / down tilts 0–60°. */
    fun dragMove(dx: Float, dy: Float) {
        val lockYaw = StudioStore.detail
        val ny = if (lockYaw) StudioStore.scheme.yaw else dragYaw + dx * 300f
        val np = (dragPitch + dy * 180f).coerceIn(0f, KeyboardPose.MAX_PITCH)
        if (StudioStore.detail) return // detail view keeps its own pose; turning back to 1× restores the scheme pose
        StudioStore.livePose(ny, np)
    }

    fun dragEnd() { StudioStore.commitPose() }

    // ---------------------------------------------------------------------------------- input
    /** Press a key (real tap and the debug `press` command take this same path). [hold] keeps it down until [releaseKey]. */
    fun pressKey(id: String, hold: Boolean = false) {
        val n = nodes[id] ?: return
        if (!n.root.enabled) return
        n.pressAt = SystemClock.uptimeMillis(); n.pressed = true; n.holding = hold; n.sounded = false; n.released = false
        StudioStore.lastPressed = id
    }

    fun releaseKey(id: String) { nodes[id]?.let { it.holding = false } }

    fun keyOf(id: String): Key? = nodes[id]?.key

    // ---------------------------------------------------------------------------------- frame loop
    private suspend fun frameLoop() {
        var last = SystemClock.uptimeMillis()
        var frames = 0; var lastLog = last
        while (scope.isActive) {
            delay(16)
            val now = SystemClock.uptimeMillis()
            last = now
            frames++
            if (now - lastLog >= 5000) { KsLog.i("FRAME loops=${frames * 1000 / (now - lastLog)}/s"); frames = 0; lastLog = now }
            val s = applied ?: continue
            // pose easing (detail toggle 0.25 s)
            if (poseAnimating) {
                val t = ((now - poseAnimStart) / 250f).coerceIn(0f, 1f); val e = ease(t)
                yaw = poseAnimFrom[0] + (poseAnimTo[0] - poseAnimFrom[0]) * e
                pitch = poseAnimFrom[1] + (poseAnimTo[1] - poseAnimFrom[1]) * e
                scale = poseAnimFrom[2] + (poseAnimTo[2] - poseAnimFrom[2]) * e
                applyPose()
                if (t >= 1f) poseAnimating = false
            }
            // layout slide 0.3 s
            if (layoutAnimating) {
                val t = ((now - layoutAnimStart) / 300f).coerceIn(0f, 1f); val e = ease(t)
                nodes.values.forEach { n ->
                    n.x = n.xFrom + (n.xTo - n.xFrom) * e
                    val slide = if (n.key.numpad) (if (layoutTo == 104) (1f - e) else e) * 0.08f else 0f
                    n.root.components.get<TransformComponent>()?.setPosition(Vector3(n.x + slide, KeyboardPose.CAP_BOTTOM_Y, n.z))
                }
                if (t >= 1f) { layoutAnimating = false; nodes.values.forEach { n -> if (n.key.numpad) n.root.enabled = layoutTo == 104; place(n, 0f) } }
            }
            // colour fades
            nodes.values.forEach { n ->
                if (n.fading) {
                    val t = ((now - n.fadeStart - n.fadeDelay) / 120f)
                    if (t >= 0f) {
                        val k = t.coerceAtMost(1f)
                        n.shown = Hex.lerp(n.from, n.to, k); n.fgShown = Hex.lerp(n.fgFrom, n.fgTo, k)
                        paint(n, s)
                        if (k >= 1f) n.fading = false
                    }
                }
                if (n.pressed) stepPress(n, now, s)
            }
            // breathing backlight 0.4–1.0, 3 s (design `keycapGeometry.shineThrough`)
            if (s.style == Style.SHINE && s.breathe) {
                val b = s.brightness * (0.7f + 0.3f * sin(2.0 * PI * now / 3000.0).toFloat())
                val c = scaled(s.backlight, b)
                nodes.values.forEach { it.legendMat.setEmissiveColor(c) }
            }
            // typing guide: the next key glows (design `content.tryTyping`)
            val ty = StudioStore.typing
            nodes.values.forEach { n ->
                val glow = ty != null && !ty.done && ty.nextId == n.key.id
                n.capMat.setEmissiveColor(if (glow) scaled(Hex.parse("#4FC3F7"), 0.25f + 0.25f * (0.5f + 0.5f * sin(2.0 * PI * now / 1200.0).toFloat())) else BLACK)
            }
        }
    }

    /** design `keycapGeometry.switch`: 4 mm in 70 ms, sound at 2 mm, back up in 90 ms (ease-out). */
    private fun stepPress(n: KeyNode, now: Long, s: Scheme) {
        val down = (now - n.pressAt) / 70f
        val travel: Float
        if (down < 1f || n.holding) {
            val k = down.coerceAtMost(1f)
            travel = TRAVEL * k
            if (!n.sounded && k >= 0.5f) { n.sounded = true; sound.press(s.switch, n.key) }
            n.releaseAt = now
        } else {
            if (!n.released) { n.released = true; n.releaseAt = now; sound.release(s.switch, n.key) }
            val up = ((now - n.releaseAt) / 90f).coerceIn(0f, 1f)
            travel = TRAVEL * (1f - ease(up))
            if (up >= 1f) n.pressed = false
        }
        place(n, travel)
    }

    fun close() {
        scope.cancel()
        runCatching { root.destroy() }
        owned.forEach { runCatching { it.close() } }; owned.clear()
        boardTex?.let { runCatching { it.close() } }
        nodes.clear()
        StudioStore.sceneReady = false
    }

    /** Debug `rebuild`: re-make all cap / legend meshes (profile / flip experiments). */
    fun rebuildNow() { capCache.clear(); legendCache.clear(); nodes.values.forEach { it.capProfile = null; it.legendVariant = null }; startRebuild(StudioStore.scheme) }

    /** Debug `legblend`: try another blending mode on every legend material. */
    fun legendBlend(mode: BlendingMode) { nodes.values.forEach { it.legendMat.setBlendingMode(mode) }; KsLog.i("SCENE legend blend=$mode") }

    companion object {
        /**
         * true = sample atlas cells from the bottom. The atlas is already drawn upside down (Art.legendAtlas), so false is
         * right: with true every cap showed the mirrored row's legend upside down (round 2 near-view check, `flipv off`).
         */
        @Volatile var flipV = false
        /** Keys built per frame: the emulator needs ~0.5 s per key on the main thread, so small batches avoid ANR. */
        const val BATCH = 4
        /** Main-thread budget per frame while swapping meshes after a profile / side-print switch. */
        const val SLICE_MS = 8L
        const val TRAVEL = 0.004f
        private val BLACK = Color4(0f, 0f, 0f, 1f)
        fun ease(t: Float) = 1f - (1f - t) * (1f - t)
        fun c4(c: Int) = Color4(Hex.r(c) / 255f, Hex.g(c) / 255f, Hex.b(c) / 255f, 1f)
        fun scaled(c: Int, k: Float) = Color4(Hex.r(c) / 255f * k, Hex.g(c) / 255f * k, Hex.b(c) / 255f * k, 1f)
        fun axisAngle(x: Float, y: Float, z: Float, deg: Float): Quat {
            val h = Math.toRadians(deg.toDouble()) / 2.0
            val s = sin(h).toFloat()
            return Quat(x * s, y * s, z * s, cos(h).toFloat())
        }
    }
}
