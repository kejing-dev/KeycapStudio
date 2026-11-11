package io.embodit.keycapstudio.domain

import kotlin.math.cos
import kotlin.math.sin

/** Plain vector for the pure (unit-tested) geometry; the scene converts to SDK types. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
    }
}

/**
 * Keyboard placement inside the studio volume (design `rules.keyboardPose`). Volume space: origin at the centre of the
 * volume floor, +Y up, +Z towards the user, metres. Keyboard space: origin at the centre of the case bottom.
 *
 * The pivot is the midpoint of the case's front bottom edge and sits on the floor at z = depth·scale/2, so at pitch 0
 * the keyboard is centred. Pitch (0–60°) lifts the back about that edge, yaw turns about the vertical through it.
 */
object KeyboardPose {
    /** Studio volume (design `container.windows.studio.sizeM`). */
    const val VOL_W = 0.8f
    const val VOL_H = 0.5f
    const val VOL_D = 0.8f

    const val CASE_D = 0.135f
    const val CASE_H = 0.020f
    /** Plate top (caps stand on 6 mm of switch above it). */
    const val PLATE_Y = 0.0125f
    const val CAP_BOTTOM_Y = PLATE_Y + 0.006f
    /** Case bottom to the highest cap top (OEM F row 11.2 mm). */
    const val TOTAL_H = CAP_BOTTOM_Y + 0.0112f
    const val RIM = 0.0057f
    const val DETAIL_SCALE = 1.5f
    const val DETAIL_PITCH = 35f
    const val DEFAULT_PITCH = 7f
    const val MAX_PITCH = 60f
    const val FOOT_H = 0.002f

    fun caseW(layout: Int) = if (layout == 104) 0.440f else 0.359f

    /** Keyboard-space point → volume space for the given pose. */
    fun toVolume(p: V3, layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float): V3 {
        // keyboard space -> pivot space: the front bottom edge midpoint is (0, 0, +D/2) in keyboard space
        val q = V3(p.x, p.y + FOOT_H, p.z - CASE_D / 2f) * scale
        val pr = Math.toRadians(pitchDeg.toDouble())
        val y1 = (q.y * cos(pr) - q.z * sin(pr)).toFloat()
        val z1 = (q.y * sin(pr) + q.z * cos(pr)).toFloat()
        val yr = Math.toRadians(yawDeg.toDouble())
        val x2 = (q.x * cos(yr) + z1 * sin(yr)).toFloat()
        val z2 = (-q.x * sin(yr) + z1 * cos(yr)).toFloat()
        return V3(x2, y1, z2 + CASE_D * scale / 2f)
    }

    /** The eight corners of the keyboard's bounding box (case + caps) in volume space. */
    fun corners(layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float): List<V3> {
        val w = caseW(layout) / 2f
        val d = CASE_D / 2f
        return listOf(-w, w).flatMap { x -> listOf(-FOOT_H, TOTAL_H).flatMap { y -> listOf(-d, d).map { z -> toVolume(V3(x, y, z), layout, yawDeg, pitchDeg, scale) } } }
    }

    /** True when the whole keyboard is inside the studio volume (D16: never clipped). */
    fun fits(layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float, margin: Float = 0.005f): Boolean =
        corners(layout, yawDeg, pitchDeg, scale).all {
            it.x in (-VOL_W / 2 + margin)..(VOL_W / 2 - margin) && it.y in -0.0001f..(VOL_H - margin) && it.z in (-VOL_D / 2 + margin)..(VOL_D / 2 - margin)
        }

    /** 「打开面板」chip: 6 cm above the midpoint of the case's back top edge (design `rules.boxCheck`). */
    fun openChipCenter(layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float): V3 =
        toVolume(V3(0f, TOTAL_H, -CASE_D / 2f), layout, yawDeg, pitchDeg, scale) + V3(0f, 0.06f + OPEN_CHIP_H / 2f, 0f)

    /**
     * 「当前色」chip (QA #1 q1-2): behind the keyboard's farthest point and 3 cm above its highest point (higher when a low eye would see it behind the keys), left-aligned with
     * it. Being farther than every cap it can never cover a key, and from any eye above the keyboard no cap covers it.
     * [stacked] lifts it above the 「打开面板」chip when both show.
     */
    fun colorChipCenter(layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float, stacked: Boolean = false): V3 {
        val cs = corners(layout, yawDeg, pitchDeg, scale)
        val m = 0.01f
        val x = (cs.minOf { it.x } + COLOR_CHIP_W / 2f).coerceIn(-VOL_W / 2 + m + COLOR_CHIP_W / 2f, VOL_W / 2 - m - COLOR_CHIP_W / 2f)
        val z = (cs.minOf { it.z } - CHIP_GAP).coerceAtLeast(-VOL_D / 2 + m)
        val lift = if (stacked) 0.06f + OPEN_CHIP_H + 0.02f else 0f
        // bottom edge: above the keyboard, and above every sight line from a low seated eye (LOW_EYE) over its corners
        val clear = cs.filter { it.z > z }.maxOfOrNull { c -> LOW_EYE.y + (c.y - LOW_EYE.y) * (LOW_EYE.z - z) / (LOW_EYE.z - c.z) } ?: 0f
        val bottom = maxOf(cs.maxOf { it.y } + CHIP_LIFT, clear + 0.01f)
        val y = (bottom + lift + COLOR_CHIP_H / 2f).coerceAtMost(VOL_H - m - COLOR_CHIP_H / 2f)
        return V3(x, y, z)
    }

    /** Gap behind the keyboard box, and height of the chip bottom above its top (seated eyes below the volume floor still see it). */
    const val CHIP_LIFT = 0.03f
    /** Lowest eye the chip must stay visible from: seated, 10 cm below the volume floor, 1 m in front (volume space). */
    val LOW_EYE = V3(0f, -0.1f, 1.0f)
    const val CHIP_GAP = 0.02f

    /** design `panels.openChip.physicalM` / `colorChip.physicalM` */
    const val OPEN_CHIP_W = 0.144f
    const val OPEN_CHIP_H = 0.038f
    const val COLOR_CHIP_W = 0.2f
    const val COLOR_CHIP_H = 0.08f

    /** Chips face +Z (the user) and stay upright: four corners of a chip centred at [c]. */
    fun chipCorners(c: V3, w: Float, h: Float) = listOf(V3(c.x - w / 2, c.y - h / 2, c.z), V3(c.x + w / 2, c.y - h / 2, c.z), V3(c.x - w / 2, c.y + h / 2, c.z), V3(c.x + w / 2, c.y + h / 2, c.z))

    /**
     * Inverse transform for the D35 checks: volume point → keyboard space, then a slab test against the keyboard box.
     */
    fun toKeyboard(p: V3, layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float): V3 {
        val q = V3(p.x, p.y, p.z - CASE_D * scale / 2f)
        val yr = Math.toRadians(-yawDeg.toDouble())
        val x1 = (q.x * cos(yr) + q.z * sin(yr)).toFloat()
        val z1 = (-q.x * sin(yr) + q.z * cos(yr)).toFloat()
        val pr = Math.toRadians(-pitchDeg.toDouble())
        val y2 = (q.y * cos(pr) - z1 * sin(pr)).toFloat()
        val z2 = (q.y * sin(pr) + z1 * cos(pr)).toFloat()
        val k = V3(x1, y2, z2) * (1f / scale)
        return V3(k.x, k.y - FOOT_H, k.z + CASE_D / 2f)
    }

    fun insideKeyboard(p: V3, layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float): Boolean {
        val k = toKeyboard(p, layout, yawDeg, pitchDeg, scale)
        return kotlin.math.abs(k.x) <= caseW(layout) / 2 && k.y in -FOOT_H..TOTAL_H && kotlin.math.abs(k.z) <= CASE_D / 2
    }

    /** Does the segment a→b pass through the keyboard box? (sampled every 2 mm, enough for a 2 cm thick box) */
    fun segmentHitsKeyboard(a: V3, b: V3, layout: Int, yawDeg: Float, pitchDeg: Float, scale: Float): Boolean {
        val len = kotlin.math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z))
        val n = (len / 0.002f).toInt().coerceAtLeast(2)
        return (0..n).any { i -> insideKeyboard(a + (b - a) * (i / n.toFloat()), layout, yawDeg, pitchDeg, scale) }
    }
}
