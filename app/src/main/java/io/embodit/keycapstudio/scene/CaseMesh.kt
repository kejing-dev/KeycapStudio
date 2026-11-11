package io.embodit.keycapstudio.scene

import io.embodit.keycapstudio.domain.KeyboardPose
import io.embodit.keycapstudio.domain.MeshData
import io.embodit.keycapstudio.domain.V3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The procedural aluminium case (design `layouts.ansi104.caseNote`): a flat rounded block 20 mm thick with a pocket
 * down to the plate, four 2 mm feet. Keyboard space, origin at the centre of the case bottom, metres.
 */
object CaseMesh {
    private const val CORNER = 0.006f
    private const val SEG = 6

    /** Rounded-rectangle outline (counter-clockwise seen from above), [r] corner radius. */
    private fun outline(w: Float, d: Float, r: Float): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>()
        val cx = w / 2 - r; val cz = d / 2 - r
        val centres = listOf(cx to cz, cx to -cz, -cx to -cz, -cx to cz)
        val start = listOf(0.0, -Math.PI / 2, -Math.PI, -3 * Math.PI / 2)
        for (c in 0 until 4) for (i in 0..SEG) {
            val a = start[c] - i * (Math.PI / 2) / SEG
            pts += (centres[c].first + r * cos(a).toFloat()) to (centres[c].second - r * sin(a).toFloat())
        }
        return pts
    }

    private fun wall(m: MeshData, ring: List<Pair<Float, Float>>, y0: Float, y1: Float, outward: Boolean) {
        for (i in ring.indices) {
            val (x0, z0) = ring[i]; val (x1, z1) = ring[(i + 1) % ring.size]
            var n = V3(z1 - z0, 0f, -(x1 - x0))
            val len = kotlin.math.sqrt(n.x * n.x + n.z * n.z).coerceAtLeast(1e-6f)
            n = V3(n.x / len, 0f, n.z / len)
            if ((n.x * (x0 + x1) + n.z * (z0 + z1) > 0f) != outward) n = n * -1f
            val a = m.v(V3(x0, y0, z0), n, 0f, 0f); val b = m.v(V3(x1, y0, z1), n, 1f, 0f)
            val c = m.v(V3(x1, y1, z1), n, 1f, 1f); val d = m.v(V3(x0, y1, z0), n, 0f, 1f)
            m.quad(a, b, c, d)
        }
    }

    /** Fan-free ring between an outer and an inner outline at height [y] (both with the same point count). */
    private fun ringTop(m: MeshData, outer: List<Pair<Float, Float>>, inner: List<Pair<Float, Float>>, y: Float) {
        val up = V3(0f, 1f, 0f)
        for (i in outer.indices) {
            val j = (i + 1) % outer.size
            val a = m.v(V3(outer[i].first, y, outer[i].second), up, 0f, 0f); val b = m.v(V3(outer[j].first, y, outer[j].second), up, 0f, 0f)
            val c = m.v(V3(inner[j].first, y, inner[j].second), up, 0f, 0f); val d = m.v(V3(inner[i].first, y, inner[i].second), up, 0f, 0f)
            m.quad(a, b, c, d)
        }
    }

    fun case(w: Float): MeshData {
        val d = KeyboardPose.CASE_D; val h = KeyboardPose.CASE_H; val rim = KeyboardPose.RIM
        val m = MeshData()
        val outer = outline(w, d, CORNER)
        val inner = outline(w - 2 * rim, d - 2 * rim, 0.0012f)
        wall(m, outer, 0f, h, outward = true)
        ringTop(m, outer, inner, h)
        wall(m, inner, KeyboardPose.PLATE_Y, h, outward = false)
        // bottom
        val down = V3(0f, -1f, 0f)
        val c0 = m.v(V3(0f, 0f, 0f), down, 0f, 0f)
        for (i in outer.indices) { val j = (i + 1) % outer.size
            m.tri(c0, m.v(V3(outer[j].first, 0f, outer[j].second), down, 0f, 0f), m.v(V3(outer[i].first, 0f, outer[i].second), down, 0f, 0f)) }
        // four rubber feet (2 mm), rounded footprints
        val fx = w / 2 - 0.025f; val fz = d / 2 - 0.02f
        for ((px, pz) in listOf(fx to fz, -fx to fz, fx to -fz, -fx to -fz)) {
            val foot = outline(0.02f, 0.012f, 0.004f).map { (x, z) -> (x + px) to (z + pz) }
            wall(m, foot, -KeyboardPose.FOOT_H, 0f, outward = true)
        }
        return m
    }

    /** The plate at the bottom of the pocket (dark). */
    fun plate(w: Float): MeshData {
        val rim = KeyboardPose.RIM
        val m = MeshData()
        val pw = (w - 2 * rim) / 2; val pd = (KeyboardPose.CASE_D - 2 * rim) / 2
        val up = V3(0f, 1f, 0f); val y = KeyboardPose.PLATE_Y
        m.quad(m.v(V3(-pw, y, -pd), up, 0f, 0f), m.v(V3(-pw, y, pd), up, 0f, 1f), m.v(V3(pw, y, pd), up, 1f, 1f), m.v(V3(pw, y, -pd), up, 1f, 0f))
        return m
    }
}
