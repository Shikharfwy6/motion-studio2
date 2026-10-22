package com.motionstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** After Effects style timeline: layer list on the left (eye, lock, name), time ruler and bars on the right. */
class TimelineView(context: Context, private val p: Project) : View(context) {
    var onSeek: ((Float) -> Unit)? = null
    var onSelect: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onToggleEye: ((Layer) -> Unit)? = null
    var onToggleLock: ((Layer) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val nameW = dp(124f)
    private val rulerH = dp(22f)
    private val rowH = dp(26f)
    private var pps = dp(70f)
    private var scrollX = 0f
    private var scrollY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xFF2A2A2A.toInt()
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f) }
    private val path = Path()
    private val rf = RectF()

    private var mode = 0 // 1 scrub, 2 move bar, 3 trim in, 4 trim out, 5 names column, 6 pan
    private var lx = 0f
    private var ly = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var hitLayer: Layer? = null
    private var o0 = 0f
    private var o1 = 0f
    private var o2 = 0f
    private var barChanged = false

    private fun rows(): List<Layer> = p.layers.asReversed()
    private fun xOf(t: Float) = nameW + t * pps - scrollX

    fun zoomBy(f: Float) {
        pps = (pps * f).coerceIn(dp(20f), dp(300f))
        clampScroll()
        invalidate()
    }

    fun timeAtX(x: Float): Float = ((x - nameW + scrollX) / pps).coerceIn(0f, p.duration)

    fun layerAtY(y: Float): Layer? {
        val r = rows()
        val i = ((y - rulerH + scrollY) / rowH).toInt()
        return if (y >= rulerH && i >= 0 && i < r.size) r[i] else null
    }

    /** Keeps the playhead visible while playing. */
    fun follow() {
        val x = xOf(p.time)
        if (x < nameW || x > width - dp(24f)) {
            scrollX = p.time * pps - (width - nameW) * 0.25f
            clampScroll()
        }
    }

    private fun clampScroll() {
        val maxX = max(0f, p.duration * pps + dp(60f) - (width - nameW))
        val maxY = max(0f, p.layers.size * rowH - (height - rulerH))
        scrollX = scrollX.coerceIn(0f, maxX)
        scrollY = scrollY.coerceIn(0f, maxY)
    }

    private fun typeColor(l: Layer): Int = if (l.tag != 0) TAG_COLORS[l.tag] else when (l.type) {
        T_TEXT -> 0xFF3B82F6.toInt()
        T_SHAPE -> 0xFFB33939.toInt()
        T_IMAGE -> 0xFF22C55E.toInt()
        T_CAMERA -> 0xFF64748B.toInt()
        T_PARTICLES -> 0xFFF59E0B.toInt()
        else -> 0xFF8B5CF6.toInt()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1A1A1A.toInt())
        clampScroll()
        val rows = rows()
        val w = width.toFloat()
        val h = height.toFloat()

        // tracks
        c.save()
        c.clipRect(nameW, rulerH, w, h)
        for ((i, l) in rows.withIndex()) {
            val top = rulerH + i * rowH - scrollY
            if (top > h || top + rowH < rulerH) continue
            val isSel = l === p.sel
            if (isSel) {
                fill.color = 0x224DA3FF
                c.drawRect(nameW, top, w, top + rowH, fill)
            }
            c.drawLine(nameW, top + rowH, w, top + rowH, line)
            val x0 = xOf(l.start)
            val x1 = max(xOf(l.end), x0 + dp(6f))
            fill.color = typeColor(l)
            fill.alpha = if (isSel) 255 else 190
            rf.set(x0, top + dp(4f), x1, top + rowH - dp(4f))
            c.drawRoundRect(rf, dp(3f), dp(3f), fill)
            fill.alpha = 255
            if (isSel) {
                stroke.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(rf, dp(3f), dp(3f), stroke)
                fill.color = 0xFFFFFFFF.toInt()
                val hy = (rf.top + rf.bottom) / 2f
                c.drawRoundRect(rf.left + dp(2f), hy - dp(5f), rf.left + dp(5f), hy + dp(5f), dp(1.5f), dp(1.5f), fill)
                c.drawRoundRect(rf.right - dp(5f), hy - dp(5f), rf.right - dp(2f), hy + dp(5f), dp(1.5f), dp(1.5f), fill)
            }
            val times = HashSet<Float>()
            for (ks in l.keys) for (k in ks) times.add(k.t)
            fill.color = C_KEY
            for (t in times) {
                val kx = xOf(t)
                val ky = (rf.top + rf.bottom) / 2f
                val r = dp(4f)
                path.reset()
                path.moveTo(kx, ky - r)
                path.lineTo(kx + r, ky)
                path.lineTo(kx, ky + r)
                path.lineTo(kx - r, ky)
                path.close()
                c.drawPath(path, fill)
            }
        }
        c.restore()

        // outside of the composition
        fill.color = 0x88000000.toInt()
        val z1 = xOf(p.duration)
        if (z1 < w) c.drawRect(max(z1, nameW), rulerH, w, h, fill)

        // ruler
        fill.color = 0xFF2D2D2D.toInt()
        c.drawRect(nameW, 0f, w, rulerH, fill)
        txt.color = 0xFF9A9A9A.toInt()
        val first = max(0, floor((scrollX) / pps).toInt())
        val last = min(ceil(p.duration).toInt(), ceil((scrollX + w - nameW) / pps).toInt())
        for (s in first..last) {
            val x = xOf(s.toFloat())
            if (x < nameW) continue
            c.drawLine(x, rulerH * 0.55f, x, rulerH, line)
            c.drawText("${s}s", x + dp(3f), rulerH - dp(6f), txt)
        }

        // playhead
        val px = xOf(p.time)
        if (px >= nameW) {
            stroke.color = 0xFF4DA3FF.toInt()
            stroke.strokeWidth = dp(1.5f)
            c.drawLine(px, 0f, px, h, stroke)
            fill.color = 0xFF4DA3FF.toInt()
            path.reset()
            path.moveTo(px - dp(6f), 0f)
            path.lineTo(px + dp(6f), 0f)
            path.lineTo(px, dp(9f))
            path.close()
            c.drawPath(path, fill)
        }

        // layer list column
        c.save()
        c.clipRect(0f, 0f, nameW, h)
        fill.color = 0xFF262626.toInt()
        c.drawRect(0f, 0f, nameW, h, fill)
        fill.color = 0xFF2D2D2D.toInt()
        c.drawRect(0f, 0f, nameW, rulerH, fill)
        txt.color = 0xFF4DA3FF.toInt()
        txt.textSize = dp(11f)
        c.drawText(fmtTime(p.time), dp(8f), rulerH - dp(6f), txt)
        txt.textSize = dp(10f)
        c.save()
        c.clipRect(0f, rulerH, nameW, h)
        for ((i, l) in rows.withIndex()) {
            val top = rulerH + i * rowH - scrollY
            if (top > h || top + rowH < rulerH) continue
            val cy = top + rowH / 2f
            if (l === p.sel) {
                fill.color = 0x334DA3FF
                c.drawRect(0f, top, nameW, top + rowH, fill)
            }
            // eye
            stroke.strokeWidth = dp(1.4f)
            stroke.color = if (l.hidden) 0xFF666666.toInt() else 0xFFCCCCCC.toInt()
            rf.set(dp(6f), cy - dp(4f), dp(20f), cy + dp(4f))
            c.drawOval(rf, stroke)
            fill.color = stroke.color
            c.drawCircle(dp(13f), cy, dp(2f), fill)
            if (l.hidden) c.drawLine(dp(6f), cy + dp(5f), dp(20f), cy - dp(5f), stroke)
            // lock
            stroke.color = if (l.locked) 0xFFFFC247.toInt() else 0xFF666666.toInt()
            fill.color = stroke.color
            rf.set(dp(27f), cy - dp(1f), dp(37f), cy + dp(5f))
            if (l.locked) c.drawRect(rf, fill) else c.drawRect(rf, stroke)
            rf.set(dp(28.5f), cy - dp(6f), dp(35.5f), cy + dp(2f))
            c.drawArc(rf, 180f, 180f, false, stroke)
            // colour tag
            fill.color = typeColor(l)
            c.drawRect(dp(42f), cy - dp(5f), dp(47f), cy + dp(5f), fill)
            // name
            txt.color = if (l.hidden) 0xFF777777.toInt() else 0xFFDDDDDD.toInt()
            val label = if (l.name.length > 12) l.name.take(11) + "\u2026" else l.name
            c.drawText(label, dp(52f), cy + dp(3.5f), txt)
            c.drawLine(0f, top + rowH, nameW, top + rowH, line)
        }
        c.restore()
        c.drawLine(nameW, 0f, nameW, h, line)
        c.restore()
        stroke.strokeWidth = dp(1.5f)
    }

    private fun seek(x: Float) {
        onSeek?.invoke(timeAtX(x))
    }

    private fun tap(x: Float, y: Float) {
        val l = hitLayer
        if (mode == 5) {
            if (l == null) return
            if (x < dp(24f)) { onToggleEye?.invoke(l); invalidate(); return }
            if (x < dp(40f)) { onToggleLock?.invoke(l); invalidate(); return }
            if (p.sel !== l) { p.sel = l; onSelect?.invoke() }
            invalidate()
            return
        }
        if (mode == 6) {
            if (l == null) {
                if (p.sel != null) { p.sel = null; onSelect?.invoke() }
                return
            }
            var best = -1f
            var bd = dp(10f)
            for (ks in l.keys) for (k in ks) {
                val dist = abs(xOf(k.t) - x)
                if (dist < bd) { bd = dist; best = k.t }
            }
            if (p.sel !== l) { p.sel = l; onSelect?.invoke() }
            if (best >= 0f) onSeek?.invoke(best)
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                downY = e.y
                lx = e.x
                ly = e.y
                moved = false
                barChanged = false
                mode = 0
                hitLayer = null
                if (e.y < rulerH) {
                    if (e.x >= nameW) {
                        mode = 1
                        seek(e.x)
                    }
                } else {
                    val l = layerAtY(e.y)
                    hitLayer = l
                    if (e.x < nameW) {
                        mode = 5
                    } else {
                        mode = 6
                        if (l != null && l === p.sel && !l.locked) {
                            val x0 = xOf(l.start)
                            val x1 = max(xOf(l.end), x0 + dp(6f))
                            val edge = dp(16f)
                            if (e.x >= x0 && e.x <= x1) {
                                mode = if (e.x < x0 + edge) 3 else if (e.x > x1 - edge) 4 else 2
                                o0 = l.start
                                o1 = l.end
                                o2 = l.trim
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > dp(6f) || abs(e.y - downY) > dp(6f)) moved = true
                val l = hitLayer
                val dt = (e.x - downX) / pps
                when (mode) {
                    1 -> seek(e.x)
                    2 -> if (moved && l != null) {
                        val shift = dt.coerceIn(-o0, p.duration - o1)
                        l.start = o0 + shift
                        l.end = o1 + shift
                        barChanged = true
                        onChanged?.invoke()
                        invalidate()
                    }
                    3 -> if (moved && l != null) {
                        var ns = (o0 + dt).coerceIn(0f, o1 - 0.2f)
                        if (l.type == T_VIDEO) ns = max(ns, o0 - o2 / max(l.speed, 0.01f))
                        l.start = ns
                        if (l.type == T_VIDEO) l.trim = o2 + (ns - o0) * l.speed
                        barChanged = true
                        onChanged?.invoke()
                        invalidate()
                    }
                    4 -> if (moved && l != null) {
                        var ne = (o1 + dt).coerceIn(o0 + 0.2f, p.duration)
                        if (l.type == T_VIDEO && l.srcDur > 0f) ne = min(ne, l.start + (l.srcDur - l.trim) / max(l.speed, 0.01f))
                        l.end = ne
                        barChanged = true
                        onChanged?.invoke()
                        invalidate()
                    }
                    5 -> {
                        scrollY -= e.y - ly
                        clampScroll()
                        invalidate()
                    }
                    6 -> {
                        scrollX -= e.x - lx
                        scrollY -= e.y - ly
                        clampScroll()
                        invalidate()
                    }
                }
                lx = e.x
                ly = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) tap(e.x, e.y)
                if (barChanged) onCommit?.invoke()
                barChanged = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (barChanged) onCommit?.invoke()
                barChanged = false
            }
        }
        return true
    }
}
