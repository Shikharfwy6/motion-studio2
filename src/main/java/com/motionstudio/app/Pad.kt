package com.motionstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/** The yellow dot that acts as the mouse pointer. */
class CursorView(context: Context) : View(context) {
    var down = false
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Color.WHITE
    }

    override fun onDraw(c: Canvas) {
        fill.color = if (down) 0xFF00FFCC.toInt() else 0xE6FFCC00.toInt()
        val r = (if (down) 5f else 7f) * d
        c.drawCircle(width / 2f, height / 2f, r, fill)
        c.drawCircle(width / 2f, height / 2f, r, ring)
    }
}

/**
 * Sends synthetic touch events to the view under the virtual cursor. Each gesture is sent to one
 * of the given containers (workspace, menu bar, keyboard...) so it never disturbs the real touch
 * that is currently driving the touchpad.
 */
class Injector(private val root: View, private val targets: () -> List<ViewGroup>) {
    var injecting = false
        private set
    private var downTime = 0L
    private var target: ViewGroup? = null
    private var offX = 0f
    private var offY = 0f
    private val a = IntArray(2)
    private val b = IntArray(2)

    private fun fire(action: Int, x: Float, y: Float) {
        val t = target ?: return
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, now, action, x - offX, y - offY, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        injecting = true
        try {
            t.dispatchTouchEvent(ev)
        } finally {
            injecting = false
            ev.recycle()
        }
    }

    fun down(x: Float, y: Float) {
        root.getLocationInWindow(a)
        target = null
        for (t in targets()) {
            if (t.visibility != View.VISIBLE) continue
            t.getLocationInWindow(b)
            val l = (b[0] - a[0]).toFloat()
            val tp = (b[1] - a[1]).toFloat()
            if (x >= l && x <= l + t.width && y >= tp && y <= tp + t.height) {
                target = t
                offX = l
                offY = tp
                break
            }
        }
        downTime = SystemClock.uptimeMillis()
        fire(MotionEvent.ACTION_DOWN, x, y)
    }

    fun move(x: Float, y: Float) = fire(MotionEvent.ACTION_MOVE, x, y)

    fun up(x: Float, y: Float) {
        fire(MotionEvent.ACTION_UP, x, y)
        target = null
    }
}

/**
 * The touchpad: slide = move the cursor, tap = click, press and hold = the cursor behaves like a
 * finger that is pressed down (drag, scroll, drag and drop, resize), two fingers = scroll drag.
 */
class TouchpadLayout(
    context: Context,
    private val cursor: CursorView,
    private val inj: Injector,
    private val limits: () -> FloatArray
) : LinearLayout(context) {
    var cx = 0f
        private set
    var cy = 0f
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val sens = 1.7f
    private var state = 0 // 0 none, 1 pending, 2 moving, 3 holding (pressed), 4 two-finger scroll, 5 ignore
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var sLastX = 0f
    private var sLastY = 0f
    private var offX = 0f
    private var offY = 0f

    private val holdRun = Runnable {
        if (state == 1) {
            state = 3
            cursor.down = true
            cursor.invalidate()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            inj.down(cx, cy)
        }
    }

    fun placeCursor(x: Float, y: Float) {
        val lim = limits()
        cx = x.coerceIn(0f, lim[0])
        cy = y.coerceIn(0f, lim[1])
        cursor.translationX = cx - cursor.width / 2f
        cursor.translationY = cy - cursor.height / 2f
    }

    private fun click() {
        cursor.down = true
        cursor.invalidate()
        inj.down(cx, cy)
        handler.postDelayed({
            inj.up(cx, cy)
            cursor.down = false
            cursor.invalidate()
        }, 60)
    }

    private fun avgX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (inj.injecting) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                downX = e.x
                downY = e.y
                downT = e.eventTime
                state = 1
                handler.postDelayed(holdRun, 380)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    handler.removeCallbacks(holdRun)
                    if (state == 3) {
                        inj.up(cx, cy)
                        cursor.down = false
                        cursor.invalidate()
                    }
                    state = 4
                    offX = 0f
                    offY = 0f
                    sLastX = avgX(e)
                    sLastY = avgY(e)
                    inj.down(cx, cy)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == 4 && e.pointerCount >= 2) {
                    val ax = avgX(e)
                    val ay = avgY(e)
                    offX += ax - sLastX
                    offY += ay - sLastY
                    sLastX = ax
                    sLastY = ay
                    inj.move(cx + offX, cy + offY)
                } else if (e.pointerCount == 1 && state != 4 && state != 5) {
                    val dx = (e.x - lastX) * sens
                    val dy = (e.y - lastY) * sens
                    lastX = e.x
                    lastY = e.y
                    if (state == 1 && hypot(e.x - downX, e.y - downY) > slop) {
                        state = 2
                        handler.removeCallbacks(holdRun)
                    }
                    if (state == 2 || state == 3) {
                        placeCursor(cx + dx, cy + dy)
                        if (state == 3) inj.move(cx, cy)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (state == 4 && e.pointerCount <= 2) {
                    inj.up(cx + offX, cy + offY)
                    state = 5
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRun)
                when (state) {
                    1 -> if (e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - downT < 320) click()
                    3 -> {
                        inj.up(cx, cy)
                        cursor.down = false
                        cursor.invalidate()
                    }
                    4 -> inj.up(cx + offX, cy + offY)
                }
                state = 0
            }
        }
        return true
    }
}

/** On-screen keyboard. Page 1 is the main block, page 2 is navigation + numpad. */
class KeyboardView(context: Context, private val onKey: (String, Boolean, Boolean, Boolean) -> Unit) : LinearLayout(context) {
    private class K(val code: String, val label: String, val w: Float = 1f)

    private val d = resources.displayMetrics.density
    private var ctrl = false
    private var shift = false
    private var alt = false
    private val modKeys = HashMap<String, TextView>()
    private val main = LinearLayout(context)
    private val side = LinearLayout(context)

    private fun bg(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = 3f * d
        g.setStroke(1, 0xFF111111.toInt())
        return g
    }

    private fun isMod(code: String) = code.startsWith("Control") || code.startsWith("Shift") || code.startsWith("Alt")

    private fun refreshMods() {
        for ((code, v) in modKeys) {
            val on = (code.startsWith("Control") && ctrl) || (code.startsWith("Shift") && shift) || (code.startsWith("Alt") && alt)
            v.background = bg(if (on) 0xFF1473E6.toInt() else 0xFF333333.toInt())
        }
    }

    private fun press(code: String) {
        when {
            code == "__page" -> {
                val showSide = side.visibility != View.VISIBLE
                side.visibility = if (showSide) View.VISIBLE else View.GONE
                main.visibility = if (showSide) View.GONE else View.VISIBLE
            }
            code.startsWith("Control") -> { ctrl = !ctrl; refreshMods() }
            code.startsWith("Shift") -> { shift = !shift; refreshMods() }
            code.startsWith("Alt") -> { alt = !alt; refreshMods() }
            else -> {
                onKey(code, ctrl, shift, alt)
                ctrl = false
                shift = false
                alt = false
                refreshMods()
            }
        }
    }

    private fun keyView(k: K): View {
        if (k.code.isEmpty()) return View(context)
        val tv = TextView(context)
        tv.text = k.label
        tv.textSize = 8f
        tv.setTextColor(0xFFCCCCCC.toInt())
        tv.gravity = Gravity.CENTER
        tv.setTypeface(null, android.graphics.Typeface.BOLD)
        tv.background = bg(0xFF333333.toInt())
        tv.isClickable = true
        if (isMod(k.code)) modKeys[k.code] = tv
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = bg(0xFF1473E6.toInt())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.background = bg(0xFF333333.toInt())
                    press(k.code)
                    refreshMods()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.background = bg(0xFF333333.toInt())
                    refreshMods()
                    true
                }
                else -> true
            }
        }
        return tv
    }

    private fun row(items: List<K>): LinearLayout {
        val r = LinearLayout(context)
        r.orientation = HORIZONTAL
        for (k in items) {
            val lp = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, k.w)
            lp.setMargins((1.2f * d).toInt(), (1.2f * d).toInt(), (1.2f * d).toInt(), (1.2f * d).toInt())
            r.addView(keyView(k), lp)
        }
        return r
    }

    private fun page(p: LinearLayout, rows: List<List<K>>) {
        p.orientation = VERTICAL
        for (r in rows) p.addView(row(r), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun keys(vararg pairs: String): List<K> = pairs.map { K(it, it) }
    private fun sp(w: Float) = K("", "", w)

    init {
        orientation = VERTICAL
        val letters1 = "QWERTYUIOP".map { K("Key$it", "$it") }
        val letters2 = "ASDFGHJKL".map { K("Key$it", "$it") }
        val letters3 = "ZXCVBNM".map { K("Key$it", "$it") }
        page(main, listOf(
            listOf(K("Escape", "Esc"), sp(0.5f)) + (1..4).map { K("F$it", "F$it") } + sp(0.3f) +
                (5..8).map { K("F$it", "F$it") } + sp(0.3f) + (9..12).map { K("F$it", "F$it") } + K("__page", "\u25B8\u25B8", 0.9f),
            listOf(K("Backquote", "`")) + (1..9).map { K("Digit$it", "$it") } + K("Digit0", "0") + K("Minus", "-") + K("Equal", "=") + K("Backspace", "Back", 2f),
            listOf(K("Tab", "Tab", 1.5f)) + letters1 + K("BracketLeft", "[") + K("BracketRight", "]") + K("Backslash", "\\", 1.5f),
            listOf(K("CapsLock", "Caps", 1.75f)) + letters2 + K("Semicolon", ";") + K("Quote", "'") + K("Enter", "Enter", 2.25f),
            listOf(K("ShiftLeft", "Shift", 2.25f)) + letters3 + K("Comma", ",") + K("Period", ".") + K("Slash", "/") + K("ShiftRight", "Shift", 2.75f),
            listOf(K("ControlLeft", "Ctrl", 1.25f), K("MetaLeft", "Win", 1.25f), K("AltLeft", "Alt", 1.25f), K("Space", "Space", 6.25f),
                K("AltRight", "Alt", 1.25f), K("ArrowLeft", "\u25C4", 1.25f), K("ArrowUp", "\u25B2", 1.25f), K("ArrowDown", "\u25BC", 1.25f), K("ArrowRight", "\u25BA", 1.25f))
        ))
        page(side, listOf(
            listOf(K("PrintScreen", "PrtSc"), K("ScrollLock", "ScrLk"), K("Pause", "Pause"), sp(0.4f),
                K("NumLock", "Num"), K("NumpadDivide", "/"), K("NumpadMultiply", "*"), K("NumpadSubtract", "-")),
            listOf(K("Insert", "Ins"), K("Home", "Home"), K("PageUp", "PgUp"), sp(0.4f),
                K("Numpad7", "7"), K("Numpad8", "8"), K("Numpad9", "9"), K("NumpadAdd", "+")),
            listOf(K("Delete", "Del"), K("End", "End"), K("PageDown", "PgDn"), sp(0.4f),
                K("Numpad4", "4"), K("Numpad5", "5"), K("Numpad6", "6"), sp(1f)),
            listOf(sp(1f), K("ArrowUp", "\u25B2"), sp(1f), sp(0.4f),
                K("Numpad1", "1"), K("Numpad2", "2"), K("Numpad3", "3"), K("NumpadEnter", "Enter")),
            listOf(K("ArrowLeft", "\u25C4"), K("ArrowDown", "\u25BC"), K("ArrowRight", "\u25BA"), sp(0.4f),
                K("Numpad0", "0", 2f), K("NumpadDecimal", "."), K("__page", "\u25C2\u25C2"))
        ))
        side.visibility = View.GONE
        addView(main, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(side, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
