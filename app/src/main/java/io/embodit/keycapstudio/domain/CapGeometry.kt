package io.embodit.keycapstudio.domain

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Row data (design `content.keycapGeometry`, KeyV2 numbers): height at the top centre and top-face tilt. */
data class RowSpec(val heightMm: Float, val tiltDeg: Float)

data class ProfileSpec(val bottomMm: Float, val topWidthDiffMm: Float, val topDepthDiffMm: Float, val dishMm: Float, val rows: Map<CapRow, RowSpec>)

object CapProfiles {
    val OEM = ProfileSpec(
        18.05f, 5.8f, 4.0f, 1.0f,
        mapOf(CapRow.F to RowSpec(11.2f, -3f), CapRow.R1 to RowSpec(9.45f, 1f), CapRow.R2 to RowSpec(9.0f, 6f), CapRow.R3 to RowSpec(9.25f, 9f), CapRow.R4 to RowSpec(9.25f, 10f)),
    )

    /** Cherry (GMK measured): top 11.85 (wide) × 14.64 (deep) on an 18.16 base, read literally from the design package. */
    val CHERRY = ProfileSpec(
        18.16f, 18.16f - 11.85f, 18.16f - 14.64f, 0.65f,
        mapOf(CapRow.F to RowSpec(9.8f, 0f), CapRow.R1 to RowSpec(9.8f, 0f), CapRow.R2 to RowSpec(7.45f, 2.5f), CapRow.R3 to RowSpec(6.55f, 5f), CapRow.R4 to RowSpec(7.35f, 11.5f)),
    )

    fun of(p: Profile) = if (p == Profile.OEM) OEM else CHERRY
}

/** A triangle soup with per-vertex normals and uvs, in metres. */
class MeshData {
    val pos = ArrayList<V3>()
    val nrm = ArrayList<V3>()
    val uv = ArrayList<Pair<Float, Float>>()
    val idx = ArrayList<Int>()

    fun v(p: V3, n: V3, u: Float, w: Float): Int { pos += p; nrm += n; uv += u to w; return pos.size - 1 }
    fun tri(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }
    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }
    val triangles get() = idx.size / 3
}

private fun norm(v: V3): V3 { val l = sqrt(v.x * v.x + v.y * v.y + v.z * v.z); return if (l < 1e-9f) V3(0f, 1f, 0f) else V3(v.x / l, v.y / l, v.z / l) }
private fun cross(a: V3, b: V3) = V3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

/**
 * Procedural keycap: a sculpted frustum with a cylindrical dish (design `keycapGeometry.mesh`). Cap space: origin at the
 * centre of the cap bottom, +Y up, +Z towards the user, metres. [uvOf] maps a cap-space XZ point to the board texture
 * (each cap gets its own uvs so a whole-board pattern reads continuously across caps).
 */
class CapShape(val profile: ProfileSpec, val row: CapRow, val wU: Float, val hU: Float) {
    private val gap = (Layout.U_MM - profile.bottomMm)
    val bottomW = (wU * Layout.U_MM - gap) / 1000f
    val bottomD = (hU * Layout.U_MM - gap) / 1000f
    val topW = bottomW - profile.topWidthDiffMm / 1000f
    val topD = bottomD - profile.topDepthDiffMm / 1000f
    private val spec = profile.rows.getValue(row)
    val height = spec.heightMm / 1000f
    private val tilt = Math.toRadians(spec.tiltDeg.toDouble())
    private val dish = profile.dishMm / 1000f
    /** The top face leans back a little (sculpt): its centre sits 0.6 mm behind the bottom centre per degree / 10. */
    private val topShiftZ = -(spec.tiltDeg / 10f) * 0.0006f

    /** Top surface point for normalised (fx, fz) in −1..1 (fz +1 = front edge), with the dish and the row tilt. */
    fun top(fx: Float, fz: Float, lift: Float = 0f): V3 {
        val lx = fx * topW / 2f
        val lz = fz * topD / 2f
        // cylindrical dish across the width (axis front-back); wide keys keep the curve within 1u at each side
        val span = minOf(topW / 2f, 0.0075f)
        val ax = kotlin.math.abs(lx)
        val inner = (topW / 2f - span).coerceAtLeast(0f)
        val t = if (ax <= inner) 0f else (ax - inner) / span
        val dy = -dish * (1f - t * t) + lift
        // tilt about the X axis through the top centre: positive tilt raises the front edge (sculpted rows form the
        // concave typing surface together with the keyboard's own pitch)
        val y = dy + (lz * sin(tilt)).toFloat()
        val z = (lz * cos(tilt)).toFloat()
        return V3(lx, height + y, z + topShiftZ)
    }

    fun topNormal(fx: Float, fz: Float): V3 {
        val e = 0.02f
        val a = top((fx - e).coerceAtLeast(-1f), fz); val b = top((fx + e).coerceAtMost(1f), fz)
        val c = top(fx, (fz - e).coerceAtLeast(-1f)); val d = top(fx, (fz + e).coerceAtMost(1f))
        return norm(cross(d - c, b - a))
    }

    fun build(nx: Int = 8, nz: Int = 5, uvOf: (Float, Float) -> Pair<Float, Float>): MeshData {
        val m = MeshData()
        // top grid, smooth normals
        val grid = Array(nz + 1) { j -> IntArray(nx + 1) { i ->
            val fx = -1f + 2f * i / nx; val fz = -1f + 2f * j / nz
            val p = top(fx, fz)
            val (u, w) = uvOf(p.x, p.z)
            m.v(p, topNormal(fx, fz), u, w)
        } }
        for (j in 0 until nz) for (i in 0 until nx) m.quad(grid[j][i], grid[j + 1][i], grid[j + 1][i + 1], grid[j][i + 1])
        // sides: bottom perimeter → top perimeter, flat normals per segment
        val perim = ArrayList<Pair<Float, Float>>() // (fx, fz) going round: back edge L→R, right B→F, front R→L, left F→B
        for (i in 0 until nx) perim += (-1f + 2f * i / nx) to -1f
        for (j in 0 until nz) perim += 1f to (-1f + 2f * j / nz)
        for (i in nx downTo 1) perim += (-1f + 2f * i / nx) to 1f
        for (j in nz downTo 1) perim += -1f to (-1f + 2f * j / nz)
        for (k in perim.indices) {
            val (fx0, fz0) = perim[k]; val (fx1, fz1) = perim[(k + 1) % perim.size]
            val t0 = top(fx0, fz0); val t1 = top(fx1, fz1)
            val b0 = V3(fx0 * bottomW / 2f, 0f, fz0 * bottomD / 2f); val b1 = V3(fx1 * bottomW / 2f, 0f, fz1 * bottomD / 2f)
            val n = norm(cross(t0 - b0, b1 - b0)).let { if (it.x * (b0.x + b1.x) + it.z * (b0.z + b1.z) < 0f) it * -1f else it }
            val uv0 = uvOf(t0.x, t0.z); val uv1 = uvOf(t1.x, t1.z)
            val ib0 = m.v(b0, n, uv0.first, uv0.second); val ib1 = m.v(b1, n, uv1.first, uv1.second)
            val it1 = m.v(t1, n, uv1.first, uv1.second); val it0 = m.v(t0, n, uv0.first, uv0.second)
            // winding: outward-facing, counter-clockwise seen from outside
            m.quad(ib0, it0, it1, ib1)
        }
        return m
    }

    /** Legend patch hugging the dished top: a square centred on the top face, [size] metres. */
    fun legendTop(size: Float, u0: Float, v0: Float, u1: Float, v1: Float, n: Int = 4): MeshData {
        val m = MeshData()
        val sx = (size / topW).coerceAtMost(0.92f); val sz = (size / topD).coerceAtMost(0.92f)
        val g = Array(n + 1) { j -> IntArray(n + 1) { i ->
            val a = i / n.toFloat(); val b = j / n.toFloat()
            val fx = (-1f + 2f * a) * sx; val fz = (-1f + 2f * b) * sz
            m.v(top(fx, fz, LEGEND_LIFT), topNormal(fx, fz), u0 + (u1 - u0) * a, v0 + (v1 - v0) * b)
        } }
        for (j in 0 until n) for (i in 0 until n) m.quad(g[j][i], g[j + 1][i], g[j + 1][i + 1], g[j][i + 1])
        return m
    }

    /** Side legend (design `keycapGeometry.sideLegend`): on the front face, lower 2/3, glyph height = face height × 0.5. */
    fun legendFront(u0: Float, v0: Float, u1: Float, v1: Float): MeshData {
        val m = MeshData()
        val bL = V3(-bottomW / 2f, 0f, bottomD / 2f); val bR = V3(bottomW / 2f, 0f, bottomD / 2f)
        val tL = top(-1f, 1f); val tR = top(1f, 1f)
        val up = tL - bL
        val faceN = norm(cross(bR - bL, up)).let { if (it.z < 0f) it * -1f else it }
        val h = 0.5f
        val c0 = 0.12f; val c1 = c0 + h  // lower part of the face
        val wFrac = 0.3f
        fun p(a: Float, b: Float): V3 {
            val bot = bL + (bR - bL) * a; val tp = tL + (tR - tL) * a
            return bot + (tp - bot) * b + faceN * LEGEND_LIFT
        }
        val q0 = m.v(p(0.5f - wFrac / 2, c0), faceN, u0, v1)
        val q1 = m.v(p(0.5f + wFrac / 2, c0), faceN, u1, v1)
        val q2 = m.v(p(0.5f + wFrac / 2, c1), faceN, u1, v0)
        val q3 = m.v(p(0.5f - wFrac / 2, c1), faceN, u0, v0)
        m.quad(q0, q1, q2, q3)
        return m
    }

    companion object {
        /** Legend patch height above the cap surface (depth precision at 1.75 m). */
        @Volatile var LEGEND_LIFT = 0.0006f
    }
}
