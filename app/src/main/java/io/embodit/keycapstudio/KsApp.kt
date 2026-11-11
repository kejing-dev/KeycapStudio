package io.embodit.keycapstudio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.embodit.keycapstudio.domain.Colorway
import io.embodit.keycapstudio.domain.Layouts
import io.embodit.keycapstudio.domain.Repo
import io.embodit.keycapstudio.domain.Settings
import io.embodit.keycapstudio.domain.StudioStore
import io.embodit.keycapstudio.platform.KsLog
import io.embodit.keycapstudio.scene.KeyboardScene
import io.embodit.keycapstudio.scene.SoundBank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Process-level services shared by the volume and the panel window (native-window-move §3.4). */
object KsApp {
    lateinit var repo: Repo
    lateinit var sound: SoundBank
    lateinit var appContext: Context
    var settings by mutableStateOf(Settings())
        private set
    /** Coach card index 0..2 while the first-run tips show, -1 when hidden (design S18). */
    var coach by mutableIntStateOf(-1)
    /** Set by Settings 「重开控制面板」; the volume (always alive) closes the panel, waits for ON_DESTROY, opens it. */
    var reopenRequested by mutableStateOf(false)
    /** Close the panel only (S17 「面板已关」, the same closeWindowContainer the system × leads to); the volume does it. */
    var closeRequested by mutableStateOf(false)
    var scene: WeakReference<KeyboardScene>? = null
    /** The volume's navigator (alive as long as the app): the debug `exit` runs the same exitApp as the button. */
    var navigator: com.pico.spatial.ui.platform.containers.SpatialNavigator? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        repo = Repo(appContext)
        val (full, tkl) = Layouts.parse(appContext.assets.open("layouts/ansi104.json").bufferedReader().readText())
        val colorways = Colorway.parse(appContext.assets.open("colorways.json").bufferedReader().readText())
        settings = repo.loadSettings()
        StudioStore.init(full, tkl, colorways, repo.loadCurrent())
        StudioStore.volume = settings.volume
        StudioStore.muted = settings.muted
        if (!settings.coachSeen) coach = 0
        sound = SoundBank(appContext)
        // autosave: 500 ms after the last change (design `stateModel.persist`)
        scope.launch {
            snapshotFlow { StudioStore.revision }.debounce(500).collect { saveNow() }
        }
        scope.launch {
            snapshotFlow { StudioStore.volume to StudioStore.muted }.collect { (v, m) -> if (v != settings.volume || m != settings.muted) updateSettings { it.copy(volume = v, muted = m) } }
        }
        KsLog.i("APP init keys=${full.keys.size}/${tkl.keys.size} colorways=${colorways.size} restored=${StudioStore.scheme.name.isNotEmpty() || StudioStore.revision > 0}")
    }

    fun saveNow() = runCatching { repo.saveCurrent(StudioStore.scheme) }.onFailure { KsLog.e("autosave failed", it) }

    fun updateSettings(f: (Settings) -> Settings) { settings = f(settings); repo.saveSettings(settings) }

    fun finishCoach() { coach = -1; updateSettings { it.copy(coachSeen = true) } }
}
