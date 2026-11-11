package io.embodit.keycapstudio.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.embodit.keycapstudio.R
import io.embodit.keycapstudio.domain.Fill
import io.embodit.keycapstudio.domain.Hex
import io.embodit.keycapstudio.domain.Key
import io.embodit.keycapstudio.domain.Layout
import io.embodit.keycapstudio.domain.Scheme
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Bitmaps drawn at run time: the legend atlas, whole-board patterns, the normal map and the scheme card. */
object Art {
    const val ATLAS = 2048
    const val COLS = 12
    const val CELL = ATLAS / COLS  // 170 px; 104 keys fit in 12 × 9

    /** Atlas cell of key index [i] as uv (u0, v0, u1, v1); v grows downwards in the bitmap. */
    fun cellUv(i: Int): FloatArray {
        val c = i % COLS; val r = i / COLS
        val pad = 2f / ATLAS
        return floatArrayOf(c * CELL / ATLAS.toFloat() + pad, r * CELL / ATLAS.toFloat() + pad, (c + 1) * CELL / ATLAS.toFloat() - pad, (r + 1) * CELL / ATLAS.toFloat() - pad)
    }

    private fun typeface(context: Context): Typeface = runCatching { ResourcesCompat.getFont(context, R.font.noto_sans_bold) }.getOrNull() ?: Typeface.DEFAULT

    /** Readable legend text for the 3D caps (always the standard English legends, design `assets.visual.legends`). */
    fun legendLines(k: Key): List<String> = when (k.id) {
        "KC_SPC" -> emptyList()
        else -> k.legend.split("\n").filter { it.isNotBlank() }
    }

    /**
     * White glyphs on transparent black, one cell per key ([side] = single centred line for front-face legends).
     */
    fun legendAtlas(context: Context, keys: List<Key>, side: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(ATLAS, ATLAS, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.translate(0f, ATLAS.toFloat()); cv.scale(1f, -1f)  // drawn upside down = the flip textures need (no copy)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = typeface(context) }
        keys.forEachIndexed { i, k ->
            val lines = legendLines(k).let { if (side) listOfNotNull(it.lastOrNull()) else it }
            if (lines.isEmpty()) return@forEachIndexed
            val x0 = (i % COLS) * CELL.toFloat(); val y0 = (i / COLS) * CELL.toFloat()
            val inner = CELL * 0.8f
            if (lines.size == 2 && !side) {
                // shifted symbol above, base below, both left aligned (classic Cherry legends)
                p.textSize = CELL * 0.34f
                p.textAlign = Paint.Align.LEFT
                cv.drawText(lines[0], x0 + CELL * 0.18f, y0 + CELL * 0.42f, p)
                cv.drawText(lines[1], x0 + CELL * 0.18f, y0 + CELL * 0.80f, p)
            } else {
                val text = lines.joinToString(" ")
                p.textAlign = Paint.Align.CENTER
                p.textSize = if (text.length <= 2) CELL * 0.50f else CELL * 0.28f
                val words = if (p.measureText(text) > inner && text.contains(' ')) text.split(' ') else listOf(text)
                while (words.maxOf { p.measureText(it) } > inner && p.textSize > 12f) p.textSize -= 2f
                val lh = p.textSize * 1.1f
                val top = y0 + CELL / 2f - lh * (words.size - 1) / 2f + p.textSize * 0.35f
                words.forEachIndexed { j, w -> cv.drawText(w, x0 + CELL / 2f, top + j * lh, p) }
            }
        }
        return bmp
    }

    /** Textures upload bottom-up on this SDK (BellArt finding): flip so v = 0 is the top row. */
    private fun flip(b: Bitmap): Bitmap {
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, false).also { if (it !== b) b.recycle() }
    }

    const val BOARD_W = 2048
    const val BOARD_H = 640

    /** Whole-board fill (gradient / themed / imported picture) in board uv space: u → +X, v → towards the user. */
    fun boardTexture(scheme: Scheme, layout: Layout, importDir: File): Bitmap {
        val bmp = Bitmap.createBitmap(BOARD_W, BOARD_H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val pxPerMm = BOARD_W / (layout.widthU * Layout.U_MM)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (scheme.effectiveFill) {
            Fill.SOLID -> cv.drawColor(scheme.themeA)
            Fill.GRADIENT -> {
                val a = scheme.gradientA; val b = scheme.gradientB
                p.shader = when (scheme.gradientDir) {
                    0 -> LinearGradient(0f, 0f, BOARD_W.toFloat(), 0f, a, b, Shader.TileMode.CLAMP)
                    1 -> LinearGradient(0f, 0f, 0f, BOARD_H.toFloat(), a, b, Shader.TileMode.CLAMP)
                    2 -> LinearGradient(0f, 0f, BOARD_W.toFloat(), BOARD_H.toFloat(), a, b, Shader.TileMode.CLAMP)
                    else -> RadialGradient(BOARD_W / 2f, BOARD_H / 2f, BOARD_W / 2f, a, b, Shader.TileMode.CLAMP)
                }
                cv.drawRect(0f, 0f, BOARD_W.toFloat(), BOARD_H.toFloat(), p)
            }
            Fill.THEMED -> if (scheme.theme == "image") drawImage(cv, scheme.image?.let { File(importDir, it) }) ?: cv.drawColor(scheme.themeA)
                else theme(cv, scheme.theme, scheme.themeA, scheme.themeB, pxPerMm)
        }
        return flip(bmp)
    }

    private fun drawImage(cv: Canvas, file: File?): Unit? {
        if (file == null || !file.isFile) return null
        val src = BitmapFactory.decodeFile(file.path) ?: return null
        // cover crop to the board aspect
        val s = maxOf(BOARD_W / src.width.toFloat(), BOARD_H / src.height.toFloat())
        val w = src.width * s; val h = src.height * s
        cv.drawBitmap(src, null, RectF((BOARD_W - w) / 2f, (BOARD_H - h) / 2f, (BOARD_W + w) / 2f, (BOARD_H + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return Unit
    }

    /** design `content.patterns.themed`: eight procedural patterns, fixed seeds. */
    fun theme(cv: Canvas, id: String, a: Int, b: Int, mm: Float) {
        val w = cv.width.toFloat(); val h = cv.height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(20261104)
        cv.drawColor(a)
        p.color = b
        when (id) {
            "dots" -> { val step = 6f * mm; var y = step / 2; var row = 0
                while (y < h) { var x = if (row % 2 == 0) step / 2 else step; while (x < w) { cv.drawCircle(x, y, 1.5f * mm, p); x += step }; y += step * 0.87f; row++ } }
            "stripes" -> { p.strokeWidth = 2f * mm; var x = -h; while (x < w + h) { cv.drawLine(x, 0f, x + h, h, p); x += 4f * mm } }
            "sakura" -> repeat(70) {
                val cx = rnd.nextFloat() * w; val cy = rnd.nextFloat() * h; val r = (3f + rnd.nextFloat() * 2f) * mm
                p.color = Hex.lerp(b, android.graphics.Color.WHITE, rnd.nextFloat() * 0.3f)
                val rot = rnd.nextFloat() * 72f
                for (k in 0 until 5) { val ang = Math.toRadians((rot + k * 72f).toDouble()); cv.drawCircle(cx + (r * 0.55f * cos(ang)).toFloat(), cy + (r * 0.55f * sin(ang)).toFloat(), r * 0.45f, p) }
                p.color = Hex.lerp(b, a, 0.5f); cv.drawCircle(cx, cy, r * 0.18f, p)
            }
            "pixel" -> { val s = 4f * mm; val c3 = Hex.lerp(a, b, 0.5f); var y = 0f
                while (y < h) { var x = 0f; while (x < w) { p.color = listOf(a, b, c3)[rnd.nextInt(3)]; cv.drawRect(x, y, x + s, y + s, p); x += s }; y += s } }
            "stars" -> { cv.drawColor(Hex.lerp(a, android.graphics.Color.BLACK, 0.6f))
                repeat(500) { p.color = Hex.lerp(b, android.graphics.Color.WHITE, rnd.nextFloat()); cv.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, (0.2f + rnd.nextFloat() * 0.5f) * mm, p) }
                p.color = b; listOf(0.22f to 0.35f, 0.71f to 0.6f).forEach { (u, v) -> star(cv, u * w, v * h, 9f * mm, p) } }
            "camo" -> { val c3 = Hex.lerp(a, b, 0.45f); val c4 = Hex.lerp(b, android.graphics.Color.BLACK, 0.35f)
                listOf(c3, b, c4).forEachIndexed { layer, col -> p.color = col
                    repeat(90 - layer * 20) { val cx = rnd.nextFloat() * w; val cy = rnd.nextFloat() * h; val r = (5f + rnd.nextFloat() * 9f) * mm
                        val path = Path(); for (k in 0..9) { val ang = k * Math.PI / 5; val rr = r * (0.6f + rnd.nextFloat() * 0.5f)
                            val px = cx + (rr * cos(ang)).toFloat(); val py = cy + (rr * 0.7f * sin(ang)).toFloat(); if (k == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                        path.close(); cv.drawPath(path, p) } } }
            "marble" -> { p.style = Paint.Style.STROKE
                repeat(40) { val path = Path(); var x = rnd.nextFloat() * w; var y = 0f; path.moveTo(x, y)
                    while (y < h) { x += (rnd.nextFloat() - 0.5f) * 18f * mm; y += 6f * mm; path.lineTo(x, y) }
                    p.strokeWidth = (0.2f + rnd.nextFloat() * 0.8f) * mm; p.color = Hex.lerp(b, a, rnd.nextFloat() * 0.6f); cv.drawPath(path, p) } }
            "grid" -> { p.strokeWidth = 0.3f * mm; var x = 0f; while (x < w) { cv.drawLine(x, 0f, x, h, p); x += 5f * mm }
                var y = 0f; while (y < h) { cv.drawLine(0f, y, w, y, p); y += 5f * mm } }
        }
    }

    private fun star(cv: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        for (k in 0 until 10) { val rr = if (k % 2 == 0) r else r * 0.42f; val ang = Math.PI / 2 + k * Math.PI / 5
            val x = cx + (rr * cos(ang)).toFloat(); val y = cy - (rr * sin(ang)).toFloat(); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close(); cv.drawPath(path, p)
    }

    /** PBT grain (design `materials.pbt.note`): a 256² tangent-space noise normal map. */
    fun grainNormal(): Bitmap {
        val n = 256
        val rnd = Random(7)
        val hgt = FloatArray(n * n) { rnd.nextFloat() }
        // two box blurs → fine grain
        repeat(2) { for (i in hgt.indices) { val x = i % n; val y = i / n; hgt[i] = (hgt[i] + hgt[y * n + (x + 1) % n] + hgt[((y + 1) % n) * n + x]) / 3f } }
        val px = IntArray(n * n) { i -> val x = i % n; val y = i / n
            val dx = (hgt[y * n + (x + 1) % n] - hgt[y * n + (x + n - 1) % n]) * 1.6f
            val dy = (hgt[((y + 1) % n) * n + x] - hgt[((y + n - 1) % n) * n + x]) * 1.6f
            val r = ((-dx * 0.5f + 0.5f) * 255).toInt().coerceIn(0, 255); val g = ((-dy * 0.5f + 0.5f) * 255).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or 0xF0 }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /**
     * Scheme card PNG 2560 × 1440 (design `rules.exportCard`): top two thirds a top view of the keyboard in the scheme's
     * colours, bottom third the scheme facts. [lines] are already localised.
     */
    fun schemeCard(context: Context, scheme: Scheme, layout: Layout, importDir: File, title: String, lines: List<String>, w: Int = 2560, h: Int = 1440): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Hex.parse("#1F3A3D"))
        val tf = typeface(context)
        val boardH = h * 2f / 3f
        val unit = minOf((w * 0.9f) / (layout.widthU + 0.6f), (boardH * 0.86f) / (layout.heightU + 0.6f))
        val bw = (layout.widthU + 0.6f) * unit; val bh = (layout.heightU + 0.6f) * unit
        val ox = (w - bw) / 2f; val oy = (boardH - bh) / 2f + h * 0.02f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = scheme.caseColor
        cv.drawRoundRect(RectF(ox, oy, ox + bw, oy + bh), unit * 0.25f, unit * 0.25f, p)
        val fillTex = if (scheme.effectiveFill != Fill.SOLID) boardTexture(scheme, layout, importDir).let { flip(it) } else null
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = unit * 0.24f }
        val sx = ox + unit * 0.3f; val sy = oy + unit * 0.3f
        layout.keys.forEach { k ->
            val r = RectF(sx + k.x * unit + unit * 0.05f, sy + k.y * unit + unit * 0.05f, sx + (k.x + k.w) * unit - unit * 0.05f, sy + (k.y + k.h) * unit - unit * 0.05f)
            if (fillTex != null) {
                val src = android.graphics.Rect((k.x / layout.widthU * fillTex.width).toInt(), (k.y / layout.heightU * fillTex.height).toInt(),
                    ((k.x + k.w) / layout.widthU * fillTex.width).toInt(), ((k.y + k.h) / layout.heightU * fillTex.height).toInt())
                cv.save(); cv.clipRect(r); cv.drawBitmap(fillTex, src, r, Paint(Paint.FILTER_BITMAP_FLAG)); cv.restore()
            } else {
                p.color = scheme.colors[k.id]?.bg ?: android.graphics.Color.GRAY
                cv.drawRoundRect(r, unit * 0.1f, unit * 0.1f, p)
            }
            txt.color = scheme.legendOf(k.id)
            legendLines(k).lastOrNull()?.let { cv.drawText(it, r.left + unit * 0.14f, r.top + unit * 0.36f, txt) }
        }
        fillTex?.recycle()
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; color = Hex.parse("#F4F1EA"); textSize = h * 0.05f; isFakeBoldText = true }
        cv.drawText(scheme.name.ifBlank { title }, w * 0.06f, boardH + h * 0.08f, t)
        t.textSize = h * 0.028f; t.isFakeBoldText = false; t.color = Hex.parse("#E1DBD1")
        lines.forEachIndexed { i, s -> cv.drawText(s, w * 0.06f, boardH + h * 0.14f + i * h * 0.042f, t) }
        t.textAlign = Paint.Align.RIGHT; t.color = Hex.parse("#F1BEB0")
        cv.drawText(title, w * 0.94f, h * 0.95f, t)
        return bmp
    }

    /** Scheme list thumbnail: the card at 480 × 270. */
    fun thumb(card: Bitmap): Bitmap = Bitmap.createScaledBitmap(card, 480, 270, true)
}
