package io.embodit.keycapstudio

import io.embodit.keycapstudio.input.parseLoggedPath
import io.embodit.keycapstudio.input.resolvePath
import io.embodit.keycapstudio.scene.Hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hit replay (board-input template): real hits report the mesh child, so names are resolved up the parent chain.
 * Same name rules as StudioTargets: `key:<keycode>`, `rim`.
 */
class TargetReplayTest {
    private fun resolve(name: String): Hit? = when {
        name.startsWith("key:") -> Hit.KeyHit(name.removePrefix("key:"))
        name == "rim" -> Hit.Rim
        else -> null
    }

    @Test fun capMeshResolvesToItsKey() =
        assertEquals(Hit.KeyHit("KC_J"), resolvePath(parseLoggedPath("cap:KC_J < key:KC_J < caseRoot < pivot < studioRoot"), ::resolve))

    @Test fun legendMeshResolvesToItsKey() =
        assertEquals(Hit.KeyHit("KC_SPC"), resolvePath(parseLoggedPath("legend:KC_SPC < key:KC_SPC < caseRoot"), ::resolve))

    @Test fun caseAndStripResolveToRim() {
        assertEquals(Hit.Rim, resolvePath(parseLoggedPath("rim < caseRoot < pivot"), ::resolve))
    }

    @Test fun caseMeshAndPlateAreNotTargets() {
        assertNull(resolvePath(parseLoggedPath("case < caseRoot < pivot < studioRoot"), ::resolve))
        assertNull(resolvePath(parseLoggedPath("plate < caseRoot"), ::resolve))
    }
}
