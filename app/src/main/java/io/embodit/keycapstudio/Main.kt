package io.embodit.keycapstudio

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.keycapstudio.ui.PanelWindow
import io.embodit.keycapstudio.ui.StudioVolume

/** Id of the default volumetric container (AndroidManifest `pico.spatial.windowcontainer.id`). */
const val STUDIO_ID = "Studio"
const val PANEL_ID = "Panel"
const val PANEL_TAG = "panel"

/**
 * App entry (design `container`: IN_VOLUME, window model `volume_plus_system_panel`, skeleton A): the default container
 * is the Shared Space volume with the 1:1 keyboard; the control panel is a second, planar system window placed to the
 * right of the volume by the system (`Placement.right`, 56 dp). Both are system windows: moving, closing and
 * minimising are the system's (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { StudioVolume() }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 760.dp),
            placement = { containers.firstOrNull { it.id == STUDIO_ID }?.let { Placement.right(it, 56.dp) } ?: Placement.none() },
        ) { PanelWindow() }
    }
