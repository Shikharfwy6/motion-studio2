package com.motionstudio.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot

/* ---------------- drag and drop ---------------- */

/** Full-screen transparent layer that shows the dragged item and reports where it was dropped. */
class DragLayer(context: Context) : FrameLayout(context) {
    private val ghost = TextView(context)
    private val d = resources.displayMetrics.density
    private val a = IntArray(2)
    private val b = IntArray(2)
    private var payload: Any? = null
    var active = false
        private set
    var onDrop: ((Any, Float, Float) -> Unit)? = null

    init {
        ghost.setTextColor(Color.WHITE)
        ghost.textSize = 11f
        ghost.setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
        val g = GradientDrawable()
        g.setColor(0xEE1473E6.toInt())
        g.cornerRadius = 8 * d
        ghost.background = g
        ghost.visibility = View.GONE
        addView(ghost, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Position of a touch inside [v], in this layer's coordinates. */
    fun pos(v: View, e: MotionEvent): FloatArray {
        v.getLocationInWindow(a)
        getLocationInWindow(b)
        return floatArrayOf(a[0] - b[0] + e.x, a[1] - b[1] + e.y)
    }

    /** Rectangle of [v] in this layer's coordinates: left, top, right, bottom. */
    fun rectOf(v: View): FloatArray {
        v.getLocationInWindow(a)
        getLocationInWindow(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        return floatArrayOf(l, t, l + v.width, t + v.height)
    }

    fun inside(v: View, x: Float, y: Float): Boolean {
        val r = rectOf(v)
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]
    }

    fun begin(label: String, data: Any, x: Float, y: Float) {
        payload = data
        ghost.text = label
        ghost.visibility = View.VISIBLE
        active = true
        move(x, y)
    }

    fun move(x: Float, y: Float) {
        ghost.x = x - ghost.width / 2f
        ghost.y = y - ghost.height - 14 * d
    }

    fun end(x: Float, y: Float) {
        if (!active) return
        active = false
        ghost.visibility = View.GONE
        val pl = payload
        payload = null
        if (pl != null) onDrop?.invoke(pl, x, y)
    }

    fun cancel() {
        active = false
        payload = null
        ghost.visibility = View.GONE
    }
}

/** Tap = [onClick], press and hold then move = drag [data] to a drop target. Works with finger and with the virtual cursor. */
fun attachDrag(v: View, drag: DragLayer, label: String, data: Any, onClick: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
    val last = FloatArray(2)
    var dragging = false
    var moved = false
    var downX = 0f
    var downY = 0f
    val start = Runnable {
        dragging = true
        v.parent?.requestDisallowInterceptTouchEvent(true)
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        drag.begin(label, data, last[0], last[1])
    }
    v.setOnTouchListener { view, e ->
        val p = drag.pos(view, e)
        last[0] = p[0]
        last[1] = p[1]
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                dragging = false
                h.postDelayed(start, 350)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    drag.move(p[0], p[1])
                } else if (hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    h.removeCallbacks(start)
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(start)
                if (dragging) drag.end(p[0], p[1]) else if (!moved) onClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(start)
                if (dragging) drag.cancel()
                dragging = false
            }
        }
        true
    }
}

/* ---------------- panel resizer ---------------- */

/** Thin bar between panels. [horizontalBar] true = it is a horizontal bar (drag up/down). */
class Resizer(context: Context, private val horizontalBar: Boolean, private val onDrag: (Float) -> Unit) : View(context) {
    private val loc = IntArray(2)
    private var last = 0f

    init {
        setBackgroundColor(0xFF141414.toInt())
    }

    private fun pos(e: MotionEvent): Float {
        getLocationInWindow(loc)
        return if (horizontalBar) loc[1] + e.y else loc[0] + e.x
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                last = pos(e)
                setBackgroundColor(0xFF1473E6.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                val p = pos(e)
                onDrag(p - last)
                last = p
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setBackgroundColor(0xFF141414.toInt())
        }
        return true
    }
}

/* ---------------- Project panel: whole folders, drag into the timeline ---------------- */

class DocItem(val name: String, val uri: Uri, val mime: String, val isDir: Boolean, val depth: Int) {
    val isMedia get() = mime.startsWith("image/") || mime.startsWith("video/")
}

object FolderScan {
    /** Lists a picked folder (and sub-folders, 4 levels deep). The folder itself is the first entry. */
    fun list(ctx: Context, tree: Uri): List<DocItem> {
        val out = ArrayList<DocItem>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Folder" }
        out.add(DocItem(rootName, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, true, 0))

        fun walk(docId: String, depth: Int) {
            val kids = ArrayList<DocItem>()
            val ids = HashMap<DocItem, String>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: "?"
                    val mime = c.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val item = DocItem(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), mime, isDir, depth)
                    kids.add(item)
                    ids[item] = id
                }
            }
            kids.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            for (k in kids) {
                out.add(k)
                if (k.isDir && depth < 4) walk(ids[k] ?: continue, depth + 1)
            }
        }
        walk(rootId, 1)
        return out
    }
}

class ProjectPanel(
    context: Context,
    private val drag: DragLayer,
    private val onImportFolder: () -> Unit,
    private val onPick: (DocItem) -> Unit
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val list = LinearLayout(context)
    private var items: List<DocItem> = emptyList()
    private val collapsed = HashSet<String>()
    private var selected: DocItem? = null

    init {
        orientation = VERTICAL
        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setBackgroundColor(0xFF2D2D2D.toInt())
        head.setPadding((8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        val t = TextView(context)
        t.text = "PROJECT"
        t.setTextColor(0xFFAAAAAA.toInt())
        t.textSize = 10f
        t.setTypeface(null, android.graphics.Typeface.BOLD)
        head.addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val imp = TextView(context)
        imp.text = "\uD83D\uDCC2 Import"
        imp.setTextColor(0xFF4DA3FF.toInt())
        imp.textSize = 10f
        imp.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        imp.setOnClickListener { onImportFolder() }
        head.addView(imp)
        addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.orientation = VERTICAL
        list.setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
        val sv = ScrollView(context)
        sv.addView(list)
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        render()
    }

    fun setItems(newItems: List<DocItem>) {
        items = newItems
        render()
    }

    private fun icon(i: DocItem): String = when {
        i.isDir -> "\uD83D\uDCC1"
        i.mime.startsWith("image/") -> "\uD83D\uDDBC\uFE0F"
        i.mime.startsWith("video/") -> "\uD83C\uDFAC"
        i.mime.startsWith("audio/") -> "\uD83C\uDFB5"
        else -> "\uD83D\uDCC4"
    }

    private fun render() {
        list.removeAllViews()
        if (items.isEmpty()) {
            val t = TextView(context)
            t.text = "Tap \"Import\" and pick a folder. Then press and hold a file and drag it onto the timeline."
            t.setTextColor(0xFF777777.toInt())
            t.textSize = 10f
            t.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), 0)
            list.addView(t)
            return
        }
        var skipBelow = -1
        for (it in items) {
            if (skipBelow >= 0) {
                if (it.depth > skipBelow) continue
                skipBelow = -1
            }
            if (it.isDir && collapsed.contains(it.uri.toString())) skipBelow = it.depth
            val row = TextView(context)
            val arrow = if (it.isDir) (if (collapsed.contains(it.uri.toString())) "\u25B8 " else "\u25BE ") else ""
            row.text = arrow + icon(it) + " " + it.name
            row.setTextColor(if (it === selected) Color.WHITE else if (it.isDir || it.isMedia) 0xFFCCCCCC.toInt() else 0xFF777777.toInt())
            row.textSize = 11f
            row.maxLines = 1
            row.ellipsize = android.text.TextUtils.TruncateAt.END
            row.setPadding(((6 + it.depth * 10) * d).toInt(), (5 * d).toInt(), (4 * d).toInt(), (5 * d).toInt())
            if (it === selected) row.setBackgroundColor(0xFF2F4F7F.toInt())
            val item = it
            if (item.isDir) {
                row.setOnClickListener {
                    val k = item.uri.toString()
                    if (!collapsed.remove(k)) collapsed.add(k)
                    render()
                }
            } else if (item.isMedia) {
                attachDrag(row, drag, icon(item) + " " + item.name, item) {
                    selected = item
                    onPick(item)
                    render()
                }
            } else {
                row.setOnClickListener { selected = item; render() }
            }
            list.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}

/* ---------------- Effects & Presets ---------------- */

class Effect(val id: String, val label: String, val enabled: Boolean = true)

object EffectCatalog {
    val cats: List<Pair<String, List<Effect>>> = listOf(
        "3D Channel" to listOf(
            Effect("move", "Position X / Y / Z"),
            Effect("rotate", "Rotation X / Y / Z"),
            Effect("3d", "Make 3D layer (on / off)"),
            Effect("camera", "3D Camera"),
            Effect("preset:Orbit 360", "Orbit 360"),
            Effect("preset:Fly in 3D", "Fly in 3D"),
            Effect("preset:Flip 3D", "Flip 3D"),
            Effect("preset:Dolly in", "Dolly in (Z)")
        ),
        "Transform" to listOf(
            Effect("scale", "Scale / Size"),
            Effect("skew", "Skew & Flip"),
            Effect("blend", "Opacity & Blend mode")
        ),
        "Color Correction" to listOf(
            Effect("fill", "Fill, Gradient, Colour"),
            Effect("border", "Stroke"),
            Effect("shadow", "Drop Shadow")
        ),
        "Generate" to listOf(
            Effect("particles", "Particles"),
            Effect("text", "Text")
        ),
        "Time" to listOf(Effect("speed", "Speed (video)")),
        "Expression" to listOf(
            Effect("wiggle", "Wiggle & Loop"),
            Effect("preset:Camera shake", "Camera shake")
        ),
        "Animation Presets" to Presets.all.keys.map { Effect("preset:$it", it) },
        "Blur & Sharpen" to listOf(Effect("none", "Gaussian Blur (coming soon)", false))
    )
}

class EffectsPanel(
    context: Context,
    private val drag: DragLayer,
    private val onApply: (Effect) -> Unit
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val list = LinearLayout(context)
    private val open = HashSet<String>()
    private var query = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF212121.toInt())
        val head = TextView(context)
        head.text = "EFFECTS & PRESETS"
        head.setTextColor(0xFFAAAAAA.toInt())
        head.textSize = 10f
        head.setTypeface(null, android.graphics.Typeface.BOLD)
        head.setBackgroundColor(0xFF2D2D2D.toInt())
        head.setPadding((8 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())
        addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val box = EditText(context)
        box.hint = "\uD83D\uDD0D Search Effects..."
        box.setHintTextColor(0xFF666666.toInt())
        box.setTextColor(0xFFCCCCCC.toInt())
        box.textSize = 11f
        box.setSingleLine(true)
        box.setBackgroundColor(0xFF141414.toInt())
        box.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s.toString().trim().lowercase()
                render()
            }
        })
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        addView(box, lp)
        list.orientation = VERTICAL
        val sv = ScrollView(context)
        sv.addView(list)
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        render()
    }

    private fun render() {
        list.removeAllViews()
        for ((cat, effects) in EffectCatalog.cats) {
            val shown = if (query.isEmpty()) effects else effects.filter { it.label.lowercase().contains(query) || cat.lowercase().contains(query) }
            if (shown.isEmpty()) continue
            val expanded = query.isNotEmpty() || open.contains(cat)
            val h = TextView(context)
            h.text = (if (expanded) "\u25BE " else "\u25B6 ") + cat
            h.setTextColor(0xFFAAAAAA.toInt())
            h.textSize = 11f
            h.setPadding((8 * d).toInt(), (5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt())
            h.setOnClickListener {
                if (!open.remove(cat)) open.add(cat)
                render()
            }
            list.addView(h)
            if (!expanded) continue
            for (e in shown) {
                val row = TextView(context)
                row.text = e.label
                row.textSize = 11f
                row.maxLines = 1
                row.ellipsize = android.text.TextUtils.TruncateAt.END
                row.setTextColor(if (e.enabled) 0xFFE0E0E0.toInt() else 0xFF666666.toInt())
                row.setPadding((22 * d).toInt(), (5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt())
                if (e.enabled) {
                    attachDrag(row, drag, "\u2728 " + e.label, e) { onApply(e) }
                }
                list.addView(row)
            }
        }
    }
}
