package io.embodit.keycapstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.keycapstudio.KsApp
import io.embodit.keycapstudio.PANEL_ID
import io.embodit.keycapstudio.PANEL_TAG
import io.embodit.keycapstudio.R
import io.embodit.keycapstudio.domain.Hex
import io.embodit.keycapstudio.domain.KeyboardPose
import io.embodit.keycapstudio.domain.SingleMode
import io.embodit.keycapstudio.domain.StudioStore
import io.embodit.keycapstudio.input.BoardInputListener
import io.embodit.keycapstudio.input.InputSource
import io.embodit.keycapstudio.input.Offset3M
import io.embodit.keycapstudio.input.TargetResolver
import io.embodit.keycapstudio.input.boardInput
import io.embodit.keycapstudio.platform.KsLog
import io.embodit.keycapstudio.scene.Hit
import io.embodit.keycapstudio.scene.KeyboardScene
import com.pico.spatial.ui.geometry.Offset3D
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/** Names → targets: `key:<keycode>` and `rim` (the case / front strip). Everything else is not a target. */
val StudioTargets = TargetResolver.byName { name ->
    when {
        name.startsWith("key:") -> Hit.KeyHit(name.removePrefix("key:"))
        name == "rim" -> Hit.Rim
        else -> null
    }
}

/** Input controller: the real pointer and the debug `press` command share [onKeyTap]. */
object StudioInput {
    fun onKeyTap(id: String) {
        val scene = KsApp.scene?.get() ?: return
        if (StudioStore.single) {
            val what = StudioStore.tapSingle(id)
            KsApp.sound.tick()
            KsLog.i("SINGLE $id $what")
            return
        }
        scene.pressKey(id)
        StudioStore.typing?.let { t ->
            if (t.done) return@let
            val now = android.os.SystemClock.uptimeMillis()
            val start = if (t.presses == 0) now else t.startMs
            val next = if (t.nextId == id) t.index + 1 else t.index
            StudioStore.typing = t.copy(index = next, presses = t.presses + 1, startMs = start, endMs = if (next >= t.ids.size) now else 0L)
        }
    }
}

@Composable
fun StudioVolume() {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    KsApp.navigator = navigator
    val scene = remember { KeyboardScene(context, KsApp.sound).also { KsApp.scene = WeakReference(it) } }
    var openChip by remember { mutableStateOf<Entity?>(null) }
    var colorChip by remember { mutableStateOf<Entity?>(null) }
    val res = localizedResources(context, KsApp.settings.lang)

    fun placeChips() {
        val (yaw, pitch, scale) = scene.pose
        val layout = StudioStore.scheme.layout
        val floor = -KeyboardPose.VOL_H / 2f
        openChip?.let { e ->
            val c = KeyboardPose.openChipCenter(layout, yaw, pitch, scale)
            e.components.get<TransformComponent>()?.setPosition(Vector3(c.x, floor + c.y, c.z))
            e.enabled = StudioStore.panelState == "ON_DESTROY"
        }
        colorChip?.let { e ->
            val c = KeyboardPose.colorChipCenter(layout, yaw, pitch, scale, stacked = StudioStore.panelState == "ON_DESTROY")
            e.components.get<TransformComponent>()?.setPosition(Vector3(c.x, floor + c.y, c.z))
            e.enabled = StudioStore.single
        }
    }
    scene.onPoseChanged = { placeChips() }

    val listener = remember {
        object : BoardInputListener<Hit> {
            override fun onTap(target: Hit?, source: InputSource, position: Offset3D?) {
                if (target is Hit.KeyHit) StudioInput.onKeyTap(target.id)
            }
            override fun onDragStart(target: Hit, source: InputSource): Boolean {
                if (target != Hit.Rim) return false
                scene.dragStart(); return true
            }
            override fun onDragMove(totalM: Offset3M) = scene.dragMove(totalM.x, totalM.y)
            // hover: the system highlight (HoverEffectComponent on each cap) is the feedback; we only log the target
            override fun onHover(target: Hit?, source: InputSource) { if (target != null) KsLog.i("HOVER $target src=${source.label}") }
            override fun onDragEnd() = scene.dragEnd()
            override fun onDragCancel() = scene.dragEnd()
        }
    }

    // focus lost / background: save now and silence (design `container.lifecycle`)
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (!focused) { KsApp.saveNow(); KsApp.sound.stopAll() } else KsApp.sound.resume() }
    // open the panel once the keyboard stands; the system places it to the right of the volume (Placement.right)
    LaunchedEffect(StudioStore.sceneReady) {
        if (StudioStore.sceneReady && StudioStore.panelState == "never") {
            KsLog.i("WINDOW open panel why=start")
            navigator.openWindowContainer(PANEL_ID, PANEL_TAG)
        }
    }
    // reopen = close, wait for ON_DESTROY, open (native-window-move §3.5); the panel comes back beside the volume
    LaunchedEffect(KsApp.reopenRequested, StudioStore.panelState) {
        if (!KsApp.reopenRequested) return@LaunchedEffect
        when (StudioStore.panelState) {
            "ON_DESTROY", "never" -> { KsApp.reopenRequested = false; KsLog.i("WINDOW open panel why=reopen"); navigator.openWindowContainer(PANEL_ID, PANEL_TAG) }
            else -> { KsLog.i("WINDOW close panel why=reopen"); navigator.closeWindowContainer(PANEL_ID, PANEL_TAG) }
        }
    }
    LaunchedEffect(KsApp.closeRequested) {
        if (!KsApp.closeRequested) return@LaunchedEffect
        KsApp.closeRequested = false
        if (StudioStore.panelState != "ON_DESTROY") { KsLog.i("WINDOW close panel why=close"); navigator.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }
    LaunchedEffect(StudioStore.panelState, StudioStore.single, openChip, colorChip) { placeChips() }
    // cold-start diagnostics at 1 / 3 / 5 s (design `rules.coldStart`)
    LaunchedEffect(Unit) {
        var t = 0L
        for (at in listOf(1000L, 3000L, 5000L)) { delay(at - t); t = at
            KsLog.i("COLD t=${at / 1000}s keys=${StudioStore.keysBuilt} ready=${StudioStore.sceneReady} panel=${StudioStore.panelState} pose=${scene.pose}") }
    }
    DisposableEffect(Unit) { onDispose { KsLog.i("VOLUME dispose"); scene.close() } }

    CompositionLocalProvider(LocalKsResources provides res) {
        PicoTheme {
            SpatialView(
                modifier = Modifier.fillMaxSize().boardInput(StudioTargets, listener),
                initial = { content, attachments ->
                    content.addEntity(scene.root)
                    attachments.entity("openChip")?.let { content.addEntity(it); it.components.get<TransformComponent>()?.setScaleVector(Vector3(0.75f, 0.75f, 0.75f)); openChip = it }
                    attachments.entity("colorChip")?.let { content.addEntity(it); it.components.get<TransformComponent>()?.setScaleVector(Vector3(0.75f, 0.75f, 0.75f)); colorChip = it }
                    try { scene.build() } catch (e: Exception) { KsLog.e("SCENE build failed", e) }
                },
                attachments = {
                    AttachmentPanel(id = "openChip") {
                        CompositionLocalProvider(LocalKsResources provides res) { PicoTheme { OpenPanelChip { KsApp.reopenRequested = true } } }
                    }
                    AttachmentPanel(id = "colorChip") {
                        CompositionLocalProvider(LocalKsResources provides res) { PicoTheme { CurrentColorChip() } }
                    }
                },
            )
        }
    }
}

@Composable
private fun OpenPanelChip(onOpen: () -> Unit) {
    if (StudioStore.panelState != "ON_DESTROY") return
    KButton(rs(R.string.open_panel), "open_panel", Modifier.width(240.dp), primary = true, icon = R.drawable.ic_tune) {
        KsLog.i("TAP open_panel chip"); onOpen()
    }
}

/** design `panels.colorChip`: swatch + HEX + [paint | accent] + [done], only in single-key mode. */
@Composable
private fun CurrentColorChip() {
    if (!StudioStore.single) return
    androidx.compose.foundation.layout.Column(
        Modifier.width(320.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillPrimary).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(StudioStore.paint)))
            Label(Hex.format(StudioStore.paint), "colorchip/hex", Modifier.weight(1f), color = PicoTheme.colorScheme.labelPrimaryLight)
            KButton(rs(R.string.done), "colorchip_done", primary = true) { StudioStore.target = io.embodit.keycapstudio.domain.PaletteTarget.MODS }
        }
        Seg(listOf(SingleMode.PAINT to rs(R.string.single_paint), SingleMode.ACCENT to rs(R.string.single_accent)), StudioStore.singleMode, "colorchip_mode") { StudioStore.singleMode = it }
    }
}
