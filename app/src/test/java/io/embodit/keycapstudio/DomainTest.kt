package io.embodit.keycapstudio

import io.embodit.keycapstudio.domain.CapProfiles
import io.embodit.keycapstudio.domain.CapRow
import io.embodit.keycapstudio.domain.CapShape
import io.embodit.keycapstudio.domain.Colorway
import io.embodit.keycapstudio.domain.Groups
import io.embodit.keycapstudio.domain.Hex
import io.embodit.keycapstudio.domain.KeyGroup
import io.embodit.keycapstudio.domain.KeyboardPose
import io.embodit.keycapstudio.domain.Layout
import io.embodit.keycapstudio.domain.Layouts
import io.embodit.keycapstudio.domain.Scheme
import io.embodit.keycapstudio.domain.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainTest {
    private val assets = File("src/main/assets")
    private val layouts = Layouts.parse(File(assets, "layouts/ansi104.json").readText())
    private val full = layouts.first
    private val tkl = layouts.second
    private val colorways = Colorway.parse(File(assets, "colorways.json").readText())

    @Test fun keyCountsAndWidths() {
        assertEquals(104, full.keys.size)
        assertEquals(87, tkl.keys.size)
        assertEquals(22.5f, full.widthU, 1e-4f)
        assertEquals(18.25f, tkl.widthU, 1e-4f)
        assertEquals(6.5f, full.heightU, 1e-4f)
        assertEquals(104, full.keys.map { it.id }.toSet().size)
    }

    @Test fun kleRowsMatchIdRows() {
        val perRow = full.keys.groupBy { it.y.toInt() }.mapValues { it.value.size }
        assertEquals(listOf(16, 21, 21, 16, 17, 13), perRow.toSortedMap().values.toList())
        assertEquals(CapRow.F, full.byId.getValue("KC_ESC").row)
        assertEquals(CapRow.R4, full.byId.getValue("KC_SPC").row)
        assertEquals(6.25f, full.byId.getValue("KC_SPC").w, 1e-4f)
        assertEquals(2f, full.byId.getValue("KC_PPLS").h, 1e-4f)
    }

    @Test fun groupsAreExclusiveAndSumUp() {
        for ((lay, a, m) in listOf(Triple(tkl, 47, 37), Triple(full, 58, 43))) {
            val g = lay.keys.groupingBy { Groups.group(it.id, Groups.ACCENT_DEFAULT) }.eachCount()
            assertEquals(a, g[KeyGroup.ALPHAS]); assertEquals(m, g[KeyGroup.MODS])
            assertEquals(2, g[KeyGroup.ACCENTS]); assertEquals(1, g[KeyGroup.SPACE])
        }
    }

    @Test fun colorwaysExpandOverrides() {
        assertEquals(16, colorways.size)
        val ids = full.keys.map { it.id }
        val dolch = colorways.first { it.id == "modern_dolch" }.colorsFor(ids, Groups.ACCENT_DEFAULT)
        assertEquals(Hex.parse("#ac5160"), dolch.getValue("KC_ENT").bg)
        assertEquals(Hex.parse("#505356"), dolch.getValue("KC_F6").bg)   // F5-F8 -> mods
        assertEquals(Hex.parse("#777b7e"), dolch.getValue("KC_SPC").bg)  // space -> base
        val metro = colorways.first { it.id == "metropolis" }.colorsFor(ids, Groups.ACCENT_DEFAULT)
        assertEquals(Hex.parse("#d54039"), metro.getValue("KC_UP").bg)
        colorways.forEach { cw -> assertEquals(104, cw.colorsFor(ids, Groups.ACCENT_DEFAULT).size) }
    }

    @Test fun schemeJsonRoundTrip() {
        val s = Scheme(name = "周五的那把", layout = 104, colors = colorways.first().colorsFor(full.keys.map { it.id }, Groups.ACCENT_DEFAULT), yaw = 30f, pitch = 20f)
        val back = Scheme.fromJson(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s, back)
    }

    @Test(expected = Exception::class) fun badJsonRejected() { Scheme.fromJson(org.json.JSONObject("{\"schemeVersion\":1,\"layout\":60,\"keyColors\":{}}")) }

    @Test fun hsvRoundTrip() {
        for (hex in listOf("#FB7279", "#1F3A3D", "#E1DBD1", "#058550")) {
            val c = Hex.parse(hex); val hsv = Hex.toHsv(c)
            val back = Hex.hsv(hsv[0], hsv[1], hsv[2])
            assertTrue(hex, kotlin.math.abs(Hex.r(back) - Hex.r(c)) <= 1 && kotlin.math.abs(Hex.g(back) - Hex.g(c)) <= 1 && kotlin.math.abs(Hex.b(back) - Hex.b(c)) <= 1)
        }
    }

    /** D16: the keyboard never leaves the 0.8 × 0.5 × 0.8 m volume (1× any yaw, 1.5× yaw 0, pitch 0–60°). */
    @Test fun keyboardFitsVolume() {
        for (layout in listOf(87, 104)) {
            for (pitch in listOf(0f, 7f, 35f, 60f)) {
                for (yaw in (0 until 360 step 15)) assertTrue("1x $layout yaw $yaw pitch $pitch", KeyboardPose.fits(layout, yaw.toFloat(), pitch, 1f))
                assertTrue("1.5x $layout pitch $pitch", KeyboardPose.fits(layout, 0f, pitch, KeyboardPose.DETAIL_SCALE))
            }
        }
    }

    @Test fun poseInverseIsExact() {
        val p = V3(0.1f, 0.02f, -0.03f)
        val v = KeyboardPose.toVolume(p, 104, 37f, 22f, 1.5f)
        val k = KeyboardPose.toKeyboard(v, 104, 37f, 22f, 1.5f)
        assertEquals(p.x, k.x, 1e-5f); assertEquals(p.y, k.y, 1e-5f); assertEquals(p.z, k.z, 1e-5f)
    }

    /** design `rules.boxCheck` (D35): 2 chips × 6 extremes × 4 eye positions: no corner inside, no sight line through. */
    @Test fun chipsClearOfKeyboard() {
        val extremes = listOf(Triple(1f, 0f, 0f), Triple(1f, 60f, 0f), Triple(1f, 0f, 90f), Triple(1f, 60f, 90f), Triple(1.5f, 0f, 0f), Triple(1.5f, 60f, 0f))
        // eye height minus volume floor: seated 1.2 / standing 1.55 with the floor at 0.9 / 1.3; the user 1.0 m in front
        val eyes = listOf(0.3f, -0.1f, 0.65f, 0.25f).map { V3(0f, it, 1.0f) }
        for (layout in listOf(87, 104)) for ((scale, pitch, yaw) in extremes) {
            val chips = listOf(
                KeyboardPose.chipCorners(KeyboardPose.openChipCenter(layout, yaw, pitch, scale), KeyboardPose.OPEN_CHIP_W, KeyboardPose.OPEN_CHIP_H),
                KeyboardPose.chipCorners(KeyboardPose.colorChipCenter(layout, yaw, pitch, scale), KeyboardPose.COLOR_CHIP_W, KeyboardPose.COLOR_CHIP_H),
            )
            for ((ci, corners) in chips.withIndex()) for (c in corners) {
                assertFalse("chip $ci corner inside $layout $scale $pitch $yaw", KeyboardPose.insideKeyboard(c, layout, yaw, pitch, scale))
                for (eye in eyes) assertFalse("chip $ci sight line $layout s=$scale p=$pitch y=$yaw eye=${eye.y}",
                    KeyboardPose.segmentHitsKeyboard(eye, c, layout, yaw, pitch, scale))
            }
        }
    }

    /** QA #1 q1-2: from 0.6 m to 1.75 m (and 0.2 m to either side) no sight line to a point of the key field crosses the colour chip. */
    @Test fun colorChipNeverCoversKeys() {
        val extremes = listOf(Triple(1f, 0f, 0f), Triple(1f, 7f, 0f), Triple(1f, 60f, 0f), Triple(1f, 7f, 90f), Triple(1f, 7f, 45f), Triple(1.5f, 35f, 0f), Triple(1.5f, 60f, 0f))
        for (layout in listOf(87, 104)) for ((scale, pitch, yaw) in extremes) for (stacked in listOf(false, true)) {
            val c = KeyboardPose.colorChipCenter(layout, yaw, pitch, scale, stacked)
            val w = KeyboardPose.COLOR_CHIP_W / 2; val h = KeyboardPose.COLOR_CHIP_H / 2
            // inside the volume
            assertTrue("chip in volume $layout $scale $pitch $yaw", c.x - w >= -KeyboardPose.VOL_W / 2 && c.x + w <= KeyboardPose.VOL_W / 2 && c.y + h <= KeyboardPose.VOL_H && c.z >= -KeyboardPose.VOL_D / 2)
            val front = KeyboardPose.corners(layout, yaw, pitch, scale).maxOf { it.z }
            // key field samples: top surface grid of the keyboard box
            val hw = KeyboardPose.caseW(layout) / 2; val hd = KeyboardPose.CASE_D / 2
            val keys = (0..10).flatMap { i -> (0..5).map { j -> KeyboardPose.toVolume(V3(-hw + 2 * hw * i / 10f, KeyboardPose.TOTAL_H, -hd + 2 * hd * j / 5f), layout, yaw, pitch, scale) } }
            for (d in listOf(0.6f, 1.0f, 1.75f)) for (ex in listOf(-0.2f, 0f, 0.2f)) for (ey in listOf(0.2f, 0.45f, 0.65f)) {
                val eye = V3(ex, ey, front + d)
                for (k in keys) {
                    if ((eye.z - c.z) * (k.z - c.z) > 0f) continue // both on the same side of the chip plane
                    val t = (c.z - eye.z) / (k.z - eye.z)
                    val hx = eye.x + (k.x - eye.x) * t; val hy = eye.y + (k.y - eye.y) * t
                    assertFalse("chip covers key $layout s=$scale p=$pitch y=$yaw d=$d eye=$ex,$ey key=$k", kotlin.math.abs(hx - c.x) <= w && kotlin.math.abs(hy - c.y) <= h)
                }
            }
        }
    }

    @Test fun capGeometryIsReal() {
        val r3 = CapShape(CapProfiles.CHERRY, CapRow.R3, 1f, 1f)
        assertEquals(0.01816f, r3.bottomW, 1e-5f)
        assertEquals(0.00655f, r3.height, 1e-6f)
        val oemF = CapShape(CapProfiles.OEM, CapRow.F, 1f, 1f)
        assertEquals(0.0112f, oemF.height, 1e-6f)
        val m = CapShape(CapProfiles.CHERRY, CapRow.R4, 6.25f, 1f).build { _, _ -> 0f to 0f }
        assertTrue("triangles ${m.triangles}", m.triangles in 60..400)
        assertEquals((6.25f * Layout.U_MM - (Layout.U_MM - 18.16f)) / 1000f, CapShape(CapProfiles.CHERRY, CapRow.R4, 6.25f, 1f).bottomW, 1e-5f)
    }
}
