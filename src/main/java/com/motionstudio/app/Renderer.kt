package com.motionstudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object Renderer {
    val shapeNames = arrayOf("Square", "Circle", "Triangle", "Star", "Hexagon", "Plus", "Arrow", "Line")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    /** Frames that were decoded for export and can be recycled after the frame is posted. */
    val toRecycle = ArrayList<Bitmap>()

    private fun poly(out: Path, pts: FloatArray) {
        out.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            out.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        out.close()
    }

    fun shapePath(kind: Int, w: Float, h: Float, radius: Float, out: Path) {
        out.reset()
        val hw = w / 2f
        val hh = h / 2f
        when (kind) {
            0 -> { rect.set(-hw, -hh, hw, hh); out.addRoundRect(rect, radius, radius, Path.Direction.CW) }
            1 -> { rect.set(-hw, -hh, hw, hh); out.addOval(rect, Path.Direction.CW) }
            2 -> poly(out, floatArrayOf(0f, -hh, hw, hh, -hw, hh))
            3 -> {
                val pts = FloatArray(20)
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val r = if (i % 2 == 0) 1f else 0.42f
                    pts[i * 2] = (cos(a) * r * hw).toFloat()
                    pts[i * 2 + 1] = (sin(a) * r * hh).toFloat()
                }
                poly(out, pts)
            }
            4 -> {
                val pts = FloatArray(12)
                for (i in 0 until 6) {
                    val a = Math.toRadians(-90.0 + i * 60.0)
                    pts[i * 2] = (cos(a) * hw).toFloat()
                    pts[i * 2 + 1] = (sin(a) * hh).toFloat()
                }
                poly(out, pts)
            }
            5 -> {
                val t = 0.18f
                poly(out, floatArrayOf(
                    -t * w, -hh, t * w, -hh, t * w, -t * h, hw, -t * h, hw, t * h, t * w, t * h,
                    t * w, hh, -t * w, hh, -t * w, t * h, -hw, t * h, -hw, -t * h, -t * w, -t * h
                ))
            }
            6 -> poly(out, floatArrayOf(
                -hw, -0.18f * h, 0.1f * w, -0.18f * h, 0.1f * w, -hh, hw, 0f,
                0.1f * w, hh, 0.1f * w, 0.18f * h, -hw, 0.18f * h
            ))
            else -> {
                val th = max(h, 6f) / 2f
                rect.set(-hw, -th, hw, th)
                out.addRoundRect(rect, th, th, Path.Direction.CW)
            }
        }
    }

    fun sizeOf(l: Layer): FloatArray {
        if (l.type != T_TEXT) return floatArrayOf(l.w, l.h)
        tp.typeface = Fonts.face(l.fontIdx)
        tp.textSize = l.size
        val lines = l.text.split("\n")
        var w = 20f
        for (ln in lines) w = max(w, tp.measureText(ln))
        return floatArrayOf(w, lines.size * l.size * 1.2f)
    }

    /** exact = true decodes the precise video frame (used for export). */
    fun draw(c: Canvas, p: Project, t: Float, exact: Boolean) {
        c.drawColor(p.bg)
        for (l in p.layers) if (l.visible(t)) drawLayer(c, p, l, t, exact)
    }

    private fun drawBmp(c: Canvas, bmp: Bitmap, l: Layer, a: Int) {
        paint.color = Color.WHITE
        paint.alpha = a
        rect.set(-l.w / 2f, -l.h / 2f, l.w / 2f, l.h / 2f)
        c.drawBitmap(bmp, null, rect, paint)
    }

    private fun drawLayer(c: Canvas, p: Project, l: Layer, t: Float, exact: Boolean) {
        val x = l.eval(P_X, t)
        val y = l.eval(P_Y, t)
        val s = l.eval(P_SCALE, t)
        val r = l.eval(P_ROT, t)
        val a = (l.eval(P_ALPHA, t).coerceIn(0f, 1f) * 255f).toInt()
        c.save()
        c.translate(x, y)
        c.rotate(r)
        c.scale(s, s)
        when (l.type) {
            T_TEXT -> {
                tp.color = l.color
                tp.alpha = a
                tp.typeface = Fonts.face(l.fontIdx)
                tp.textSize = l.size
                val fm = tp.fontMetrics
                val lines = l.text.split("\n")
                val lh = l.size * 1.2f
                for ((i, ln) in lines.withIndex()) {
                    val yy = (i - (lines.size - 1) / 2f) * lh - (fm.ascent + fm.descent) / 2f
                    c.drawText(ln, 0f, yy, tp)
                }
            }
            T_SHAPE -> {
                paint.color = l.color
                paint.alpha = a
                shapePath(l.shape, l.w, l.h, l.radius, path)
                c.drawPath(path, paint)
            }
            T_IMAGE -> {
                val m = l.media
                if (m != null) {
                    val bmp = MediaCache.image(Store.mediaPath(p.id, m))
                    if (bmp != null) drawBmp(c, bmp, l, a)
                }
            }
            T_VIDEO -> {
                val m = l.media
                if (m != null) {
                    val file = Store.mediaPath(p.id, m)
                    val src = max(0f, t - l.start + l.trim)
                    val us = (src * 1_000_000f).toLong()
                    val bmp = VideoCache.exactFrame(file, us)
                    if (bmp != null) {
                        drawBmp(c, bmp, l, a)
                        toRecycle.add(bmp)
                    }
                }
            }
        }
        c.restore()
    }
}
