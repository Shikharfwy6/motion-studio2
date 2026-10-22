package com.motionstudio.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class EditorActivity : Activity(), PanelHost {
    private lateinit var p: Project
    private lateinit var root: FrameLayout
    private lateinit var editor: EditorView
    private lateinit var gl: GLEditorView
    private lateinit var stage: FrameLayout
    private lateinit var timeline: TimelineView
    private lateinit var panel: LayerPanel
    private lateinit var drag: DragLayer
    private lateinit var projectPanel: ProjectPanel
    private lateinit var effectsPanel: EffectsPanel
    private lateinit var topBars: LinearLayout
    private lateinit var workspace: LinearLayout
    private lateinit var leftCol: LinearLayout
    private lateinit var timelinePanel: LinearLayout
    private lateinit var timelineBody: FrameLayout
    private lateinit var ctrlBody: FrameLayout
    private lateinit var ctrlMsg: TextView
    private lateinit var tabTimeline: TextView
    private lateinit var tabControls: TextView
    private lateinit var compTitle: TextView
    private lateinit var resLabel: TextView
    private lateinit var timeLabel: TextView
    private lateinit var playIcon: IconView
    private lateinit var cursor: CursorView
    private lateinit var keyboard: KeyboardView
    private lateinit var padArea: TouchpadLayout
    private val toolViews = ArrayList<TextView>()
    private val handler = Handler(Looper.getMainLooper())

    private var leftW = 0
    private var effectsH = 0
    private var timelineH = 0
    private var padH = 0
    private var tool = 0
    private var playing = false
    private var lastNs = 0L
    private var glW = 0
    private var glH = 0
    private var lastSelId = -1
    private var clip: Layer? = null
    private val hist = ArrayList<String>()
    private var hi = -1

    private val bgPalette = intArrayOf(
        Color.parseColor("#0D0E14"), Color.BLACK, Color.WHITE,
        Color.parseColor("#1E293B"), Color.parseColor("#7F1D1D"), Color.parseColor("#14532D")
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(C_TEXT)
        b.setBackgroundColor(C_PANEL2)
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(12), 0, dp(12), 0)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun header(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFAAAAAA.toInt())
        t.textSize = 10f
        t.setTypeface(null, android.graphics.Typeface.BOLD)
        t.setBackgroundColor(0xFF2D2D2D.toInt())
        t.setPadding(dp(8), dp(5), dp(8), dp(5))
        t.maxLines = 1
        return t
    }

    private fun setSize(v: View, w: Int?, h: Int?) {
        val lp = v.layoutParams
        if (w != null) lp.width = w
        if (h != null) lp.height = h
        v.layoutParams = lp
    }

    private fun applySizes() {
        setSize(leftCol, leftW, null)
        setSize(effectsPanel, null, effectsH)
        setSize(timelinePanel, null, timelineH)
        setSize(padArea, null, padH)
    }

    /* ---------------- PanelHost ---------------- */

    override fun changed() {
        editor.invalidate()
    }

    override fun title(t: String?) {
        if (!::tabControls.isInitialized) return
        tabControls.text = if (t == null) "EFFECT CONTROLS" else "EFFECT CONTROLS \u00B7 " + t.uppercase()
    }

    override fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun timelineChanged() {
        timeline.invalidate()
    }

    /* ---------------- build UI ---------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        val id = intent.getStringExtra("id")
        val loaded = if (id != null) Store.load(id) else null
        if (loaded == null) {
            finish()
            return
        }
        p = loaded
        window.statusBarColor = 0xFF1E1E1E.toInt()
        window.navigationBarColor = 0xFF1E1E1E.toInt()
        leftW = dp(118)
        effectsH = dp(150)
        timelineH = dp(170)
        padH = dp(230)

        editor = EditorView(this, p)
        timeline = TimelineView(this, p)
        panel = LayerPanel(this, p, this)
        drag = DragLayer(this)
        drag.onDrop = { data, x, y ->
            when (data) {
                is DocItem -> dropMedia(data, x, y)
                is Effect -> dropEffect(data, x, y)
                else -> {}
            }
        }
        editor.onSelect = { refreshProps() }
        editor.onEdit = { panel.refresh() }
        editor.onCommit = { timeline.invalidate(); panel.refresh(); commit() }
        editor.onTap = { t, x, y -> if (t == 7) addTextAt(x, y) else addShapeAt(x, y) }
        timeline.onSeek = { stopPlay(); setTime(it) }
        timeline.onSelect = { editor.invalidate(); refreshProps() }
        timeline.onChanged = { editor.invalidate() }
        timeline.onCommit = { commit() }
        timeline.onToggleEye = { l -> l.hidden = !l.hidden; editor.invalidate(); commit() }
        timeline.onToggleLock = { l -> l.locked = !l.locked; commit() }

        root = FrameLayout(this)
        root.setBackgroundColor(0xFF1E1E1E.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        topBars = LinearLayout(this)
        topBars.orientation = LinearLayout.VERTICAL
        topBars.addView(buildMenuBar())
        topBars.addView(buildToolBar())
        col.addView(topBars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        workspace = LinearLayout(this)
        workspace.orientation = LinearLayout.VERTICAL
        cursor = CursorView(this)
        keyboard = KeyboardView(this) { code, ctrl, shift, alt -> onKeyPress(code, ctrl, shift, alt) }
        val injector = Injector(root) { listOf(topBars, workspace, keyboard) }
        padArea = TouchpadLayout(this, cursor, injector) { floatArrayOf(root.width.toFloat(), root.height.toFloat()) }
        buildWorkspace()
        col.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildPad()
        col.addView(padArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, padH))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(drag, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(cursor, FrameLayout.LayoutParams(dp(26), dp(26)))
        setContentView(root)

        hist.add(Store.toJson(p))
        hi = 0
        setTool(0)
        setTime(0f)
        refreshProps()
        loadFolders()
        root.post { padArea.placeCursor(root.width / 2f, root.height / 3f) }
    }

    private fun buildMenuBar(): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.setBackgroundColor(0xFF262626.toInt())
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.HORIZONTAL
        for (name in listOf("File", "Edit", "Composition", "Layer", "Effect", "Animation", "View", "Window", "Help")) {
            val tv = TextView(this)
            tv.text = name
            tv.setTextColor(0xFFCCCCCC.toInt())
            tv.textSize = 12f
            tv.setPadding(dp(10), dp(7), dp(10), dp(7))
            tv.setOnClickListener { showMenu(name, tv) }
            ll.addView(tv)
        }
        hs.addView(ll)
        return hs
    }

    private fun buildToolBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xFF2B2B2B.toInt())
        bar.setPadding(dp(5), dp(4), dp(5), dp(4))
        for ((i, ch) in "VHZWCQGT".withIndex()) {
            val tv = TextView(this)
            tv.text = "$ch"
            tv.textSize = 11f
            tv.gravity = Gravity.CENTER
            tv.setOnClickListener { setTool(i) }
            val lp = LinearLayout.LayoutParams(dp(30), dp(26))
            lp.setMargins(dp(1), 0, dp(1), 0)
            toolViews.add(tv)
            bar.addView(tv, lp)
        }
        val sp = View(this)
        bar.addView(sp, LinearLayout.LayoutParams(0, 1, 1f))
        timeLabel = TextView(this)
        timeLabel.setTextColor(0xFF4DA3FF.toInt())
        timeLabel.textSize = 11f
        timeLabel.setPadding(dp(6), 0, dp(8), 0)
        bar.addView(timeLabel)
        val ex = TextView(this)
        ex.text = "Export"
        ex.setTextColor(Color.WHITE)
        ex.textSize = 11f
        ex.gravity = Gravity.CENTER
        ex.setPadding(dp(10), dp(4), dp(10), dp(4))
        val g = GradientDrawable()
        g.setColor(0xFF1473E6.toInt())
        g.cornerRadius = dp(4).toFloat()
        ex.background = g
        ex.setOnClickListener { doExport() }
        bar.addView(ex)
        return bar
    }

    private fun buildWorkspace() {
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL

        leftCol = LinearLayout(this)
        leftCol.orientation = LinearLayout.VERTICAL
        projectPanel = ProjectPanel(this, drag, { importFolder() }, { item -> toast(item.name + ": press and hold, then drag to the timeline") })
        leftCol.addView(projectPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        leftCol.addView(Resizer(this, true) { dy ->
            effectsH = (effectsH - dy.toInt()).coerceIn(dp(70), dp(380))
            applySizes()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)))
        effectsPanel = EffectsPanel(this, drag) { e -> applyEffect(e, p.sel) }
        leftCol.addView(effectsPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, effectsH))
        top.addView(leftCol, LinearLayout.LayoutParams(leftW, ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(Resizer(this, false) { dx ->
            leftW = (leftW + dx.toInt()).coerceIn(dp(80), dp(260))
            applySizes()
        }, LinearLayout.LayoutParams(dp(7), ViewGroup.LayoutParams.MATCH_PARENT))

        val center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL

        // composition preview
        val comp = LinearLayout(this)
        comp.orientation = LinearLayout.VERTICAL
        comp.setBackgroundColor(0xFF181818.toInt())
        compTitle = header("COMPOSITION")
        comp.addView(compTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stage = FrameLayout(this)
        stage.setBackgroundColor(Color.BLACK)
        gl = GLEditorView(this, p)
        editor.gl = gl
        stage.addView(gl, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        stage.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        resLabel = TextView(this)
        resLabel.setTextColor(0xFF777777.toInt())
        resLabel.textSize = 10f
        val rlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        rlp.setMargins(dp(6), 0, 0, dp(4))
        stage.addView(resLabel, rlp)
        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        val cbg = GradientDrawable()
        cbg.setColor(0xB3000000.toInt())
        cbg.cornerRadius = dp(6).toFloat()
        controls.background = cbg
        playIcon = IconView(this, IconView.PLAY)
        val start = IconView(this, IconView.START)
        val end = IconView(this, IconView.END)
        start.setOnClickListener { stopPlay(); setTime(0f) }
        playIcon.setOnClickListener { if (playing) stopPlay() else startPlay() }
        end.setOnClickListener { stopPlay(); setTime(p.duration) }
        for (v in listOf<View>(start, playIcon, end)) controls.addView(v, LinearLayout.LayoutParams(dp(34), dp(30)))
        val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        clp.bottomMargin = dp(4)
        stage.addView(controls, clp)
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutGl() }
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        slp.setMargins(dp(6), dp(6), dp(6), dp(6))
        comp.addView(stage, slp)
        center.addView(comp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        center.addView(Resizer(this, true) { dy ->
            timelineH = (timelineH - dy.toInt()).coerceIn(dp(70), dp(420))
            applySizes()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)))

        // timeline panel with the Effect Controls tab
        timelinePanel = LinearLayout(this)
        timelinePanel.orientation = LinearLayout.VERTICAL
        timelinePanel.setBackgroundColor(0xFF232323.toInt())
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setBackgroundColor(0xFF2D2D2D.toInt())
        tabTimeline = TextView(this)
        tabControls = TextView(this)
        for ((tv, label) in listOf(tabTimeline to "TIMELINE", tabControls to "EFFECT CONTROLS")) {
            tv.text = label
            tv.textSize = 10f
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            tv.setPadding(dp(10), dp(6), dp(10), dp(6))
            tv.maxLines = 1
        }
        tabTimeline.setOnClickListener { showTimelineTab() }
        tabControls.setOnClickListener { showControlsTab() }
        tabs.addView(tabTimeline)
        tabs.addView(tabControls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((label, f) in listOf("\u2212" to 0.77f, "+" to 1.3f)) {
            val z = TextView(this)
            z.text = label
            z.setTextColor(0xFFCCCCCC.toInt())
            z.textSize = 15f
            z.gravity = Gravity.CENTER
            z.setOnClickListener { timeline.zoomBy(f) }
            tabs.addView(z, LinearLayout.LayoutParams(dp(30), dp(26)))
        }
        timelinePanel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timelineBody = FrameLayout(this)
        timelineBody.addView(timeline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ctrlBody = FrameLayout(this)
        ctrlBody.setBackgroundColor(C_PANEL)
        ctrlBody.addView(panel.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ctrlMsg = TextView(this)
        ctrlMsg.text = "Select a layer to edit its effects, or drag an effect from the Effects panel onto a layer."
        ctrlMsg.setTextColor(0xFF777777.toInt())
        ctrlMsg.textSize = 12f
        ctrlMsg.gravity = Gravity.CENTER
        ctrlMsg.setPadding(dp(16), dp(16), dp(16), dp(16))
        ctrlBody.addView(ctrlMsg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ctrlBody.visibility = View.GONE
        val bodyHolder = FrameLayout(this)
        bodyHolder.addView(timelineBody, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bodyHolder.addView(ctrlBody, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        timelinePanel.addView(bodyHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        center.addView(timelinePanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, timelineH))
        top.addView(center, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        workspace.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        workspace.addView(Resizer(this, true) { dy ->
            padH = (padH - dy.toInt()).coerceIn(dp(110), dp(420))
            applySizes()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)))
        showTimelineTab()
    }

    private fun buildPad() {
        padArea.orientation = LinearLayout.VERTICAL
        padArea.setBackgroundColor(0xFF121212.toInt())
        padArea.setPadding(dp(5), dp(6), dp(5), dp(4))
        val h1 = TextView(this)
        h1.text = "TOUCHPAD: slide = move  |  tap = click  |  hold = press & drag  |  2 fingers = scroll"
        h1.setTextColor(0xFF555555.toInt())
        h1.textSize = 8f
        h1.gravity = Gravity.CENTER
        h1.maxLines = 1
        padArea.addView(h1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val klp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        klp.setMargins(0, dp(4), 0, dp(4))
        keyboard.setBackgroundColor(0xFF222222.toInt())
        keyboard.setPadding(dp(4), dp(4), dp(4), dp(4))
        padArea.addView(keyboard, klp)
        val h2 = TextView(this)
        h2.text = "\u25B8\u25B8 key = numpad / arrows page"
        h2.setTextColor(0xFF555555.toInt())
        h2.textSize = 8f
        h2.gravity = Gravity.CENTER
        padArea.addView(h2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showTimelineTab() {
        timelineBody.visibility = View.VISIBLE
        ctrlBody.visibility = View.GONE
        tabTimeline.setTextColor(Color.WHITE)
        tabTimeline.setBackgroundColor(0xFF3A3A3A.toInt())
        tabControls.setTextColor(0xFF888888.toInt())
        tabControls.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun showControlsTab() {
        timelineBody.visibility = View.GONE
        ctrlBody.visibility = View.VISIBLE
        tabControls.setTextColor(Color.WHITE)
        tabControls.setBackgroundColor(0xFF3A3A3A.toInt())
        tabTimeline.setTextColor(0xFF888888.toInt())
        tabTimeline.setBackgroundColor(Color.TRANSPARENT)
        updateCtrl()
    }

    private fun updateCtrl() {
        val has = p.sel != null
        panel.view.visibility = if (has) View.VISIBLE else View.GONE
        ctrlMsg.visibility = if (has) View.GONE else View.VISIBLE
    }

    private fun setTool(i: Int) {
        if (i == 6) {
            editor.showGrid = !editor.showGrid
            editor.invalidate()
            toast(if (editor.showGrid) "Grid on" else "Grid off")
            return
        }
        if (i == 1 || i == 2) toast("Hand and Zoom tools are coming soon")
        tool = i
        editor.tool = if (i == 1 || i == 2) 0 else i
        for ((k, tv) in toolViews.withIndex()) {
            val sel = k == i
            tv.setTextColor(if (sel) Color.WHITE else 0xFFAAAAAA.toInt())
            val g = GradientDrawable()
            g.setColor(if (sel) 0xFF1473E6.toInt() else 0xFF333333.toInt())
            g.cornerRadius = dp(3).toFloat()
            tv.background = g
        }
    }

    /* ---------------- selection ---------------- */

    private fun refreshProps() {
        val id = p.sel?.id ?: -1
        if (id != lastSelId) {
            lastSelId = id
            if (p.sel != null) panel.showGrid()
            updateCtrl()
            timeline.invalidate()
            editor.invalidate()
        } else {
            panel.refresh()
        }
    }

    private fun openControls(name: String) {
        if (p.sel == null) {
            toast("Select a layer first")
            return
        }
        showControlsTab()
        panel.open(name)
    }

    private fun applyEffect(e: Effect, target: Layer?) {
        when (e.id) {
            "camera" -> { addCamera(); return }
            "particles" -> { addParticles(); return }
            "text" -> { addText("Text", 96f); return }
        }
        val l = target ?: run { toast("Select a layer first"); return }
        if (p.sel !== l) {
            p.sel = l
            refreshProps()
        }
        if (e.id.startsWith("preset:")) {
            val name = e.id.removePrefix("preset:")
            Presets.all[name]?.invoke(l, p.time)
            editor.invalidate()
            timeline.invalidate()
            panel.refresh()
            commit()
            toast("$name applied to ${l.name}")
        } else if (e.id == "3d") {
            panel.open("3d")
        } else {
            showControlsTab()
            panel.open(e.id)
        }
    }

    /* ---------------- drag and drop targets ---------------- */

    private fun dropMedia(item: DocItem, x: Float, y: Float) {
        if (!item.isMedia) {
            toast("Only images and videos can be added")
            return
        }
        val inTl = drag.inside(timeline, x, y)
        val inComp = drag.inside(stage, x, y)
        if (!inTl && !inComp) {
            toast("Drop it on the timeline or on the composition")
            return
        }
        val start = if (inTl) timeline.timeAtX(x - drag.rectOf(timeline)[0]) else p.time
        toast("Importing " + item.name + "...")
        Thread {
            val name = Store.importMedia(applicationContext, p.id, item.uri)
            runOnUiThread {
                if (name == null) toast("Could not import that file") else addMedia(name, start)
            }
        }.start()
    }

    private fun dropEffect(e: Effect, x: Float, y: Float) {
        if (drag.inside(timeline, x, y)) {
            val l = timeline.layerAtY(y - drag.rectOf(timeline)[1])
            applyEffect(e, l ?: p.sel)
        } else if (drag.inside(stage, x, y)) {
            applyEffect(e, p.sel)
        } else {
            toast("Drop the effect on a layer in the timeline")
        }
    }

    /* ---------------- menus ---------------- */

    private fun showMenu(name: String, anchor: View) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        when (name) {
            "File" -> {
                items.add("Import folder..." to { importFolder() })
                items.add("Import image / video..." to { pick("*/*", 23) })
                items.add("Save" to { Store.save(p); toast("Saved") })
                items.add("Export video..." to { doExport() })
                items.add("Close project" to { finish() })
            }
            "Edit" -> {
                items.add("Undo" to { undo() })
                items.add("Redo" to { redo() })
                items.add("Duplicate layer" to { duplicate() })
                items.add("Copy layer" to { copyLayer() })
                items.add("Paste layer" to { pasteLayer() })
                items.add("Paste style" to { pasteStyle() })
                items.add("Delete layer" to { deleteLayer() })
            }
            "Composition" -> {
                items.add("Composition settings..." to { settings() })
                items.add("New 3D camera" to { addCamera() })
                items.add("Motion blur: change" to { cycleBlur() })
            }
            "Layer" -> {
                items.add("New text" to { addText("Title", 120f) })
                for ((i, n) in Renderer.shapeNames.withIndex()) items.add("New shape: $n" to { addShape(i) })
                items.add("New particles" to { addParticles() })
                items.add("New 3D camera" to { addCamera() })
                items.add("3D layer on / off" to { panelOpenOrToast("3d") })
                items.add("Flip horizontal" to { editSel { it.flipH = !it.flipH } })
                items.add("Flip vertical" to { editSel { it.flipV = !it.flipV } })
                items.add("Fit to comp" to { fitMode(0) })
                items.add("Fill comp" to { fitMode(1) })
                items.add("Stretch to comp" to { fitMode(2) })
                items.add("Colour tag..." to { tagDialog() })
                items.add("Hide / show" to { editSel { it.hidden = !it.hidden } })
                items.add("Lock / unlock" to { editSel { it.locked = !it.locked } })
            }
            "Effect" -> {
                for ((cat, list) in EffectCatalog.cats) {
                    for (e in list) if (e.enabled) items.add("$cat > ${e.label}" to { applyEffect(e, p.sel) })
                }
            }
            "Animation" -> {
                items.add("Keyframe: position (X Y Z)" to { keyToggle(intArrayOf(P_X, P_Y, P_Z)) })
                items.add("Keyframe: rotation" to { keyToggle(intArrayOf(P_ROT, P_RX, P_RY)) })
                items.add("Keyframe: scale" to { keyToggle(intArrayOf(P_SCALE)) })
                items.add("Keyframe: opacity" to { keyToggle(intArrayOf(P_ALPHA)) })
                items.add("Wiggle & Loop..." to { openControls("wiggle") })
                for (n in Presets.all.keys) items.add("Preset: $n" to { applyEffect(Effect("preset:$n", n), p.sel) })
            }
            "View" -> {
                items.add("Grid on / off" to { setTool(6) })
                items.add("Guides on / off" to { editor.showGuides = !editor.showGuides; editor.invalidate() })
                items.add("Timeline zoom in" to { timeline.zoomBy(1.3f) })
                items.add("Timeline zoom out" to { timeline.zoomBy(0.77f) })
            }
            "Window" -> {
                items.add("Project panel on / off" to { leftCol.visibility = if (leftCol.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
                items.add("Effect controls" to { showControlsTab() })
                items.add("Timeline" to { showTimelineTab() })
                items.add("Keyboard & touchpad on / off" to { padArea.visibility = if (padArea.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
            }
            else -> {
                items.add("Touchpad and shortcuts" to { showHelp() })
            }
        }
        val pm = PopupMenu(this, anchor)
        for ((i, it) in items.withIndex()) pm.menu.add(0, i, i, it.first)
        pm.setOnMenuItemClickListener { mi ->
            items[mi.itemId].second()
            true
        }
        pm.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Touchpad and shortcuts")
            .setMessage(
                "TOUCHPAD (bottom)\n" +
                    "  Slide one finger: move the cursor\n" +
                    "  Tap: click\n" +
                    "  Press and hold, then slide: the cursor acts like a pressed finger (drag, scroll, resize panels, drag and drop)\n" +
                    "  Two fingers: scroll\n\n" +
                    "DRAG AND DROP\n" +
                    "  Project panel > Import a folder. Press and hold a file, drag it onto the timeline.\n" +
                    "  Effects panel: tap an effect for the selected layer, or drag it onto a layer.\n\n" +
                    "KEYS\n" +
                    "  V select, W rotate, C camera orbit, Q add shape, T add text, G grid\n" +
                    "  P position (X Y Z), R rotation, S scale, T opacity\n" +
                    "  Space play, Home / End, PgUp / PgDn frame, K / J next / previous keyframe\n" +
                    "  [ ] set layer in / out, Arrow keys nudge, Delete\n" +
                    "  Ctrl+Z undo, Ctrl+D duplicate, Ctrl+C / V copy / paste, Ctrl+I import folder"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /* ---------------- keyboard shortcuts ---------------- */

    private fun onKeyPress(code: String, ctrl: Boolean, shift: Boolean, alt: Boolean) {
        val sel = p.sel
        if (ctrl) {
            when (code) {
                "KeyZ" -> if (shift) redo() else undo()
                "KeyY" -> redo()
                "KeyD" -> duplicate()
                "KeyC" -> copyLayer()
                "KeyV" -> if (shift) pasteStyle() else pasteLayer()
                "KeyS" -> { Store.save(p); toast("Saved") }
                "KeyI" -> importFolder()
                "KeyM" -> doExport()
                "KeyT" -> setTool(7)
            }
            return
        }
        when (code) {
            "KeyV" -> setTool(0)
            "KeyH" -> setTool(1)
            "KeyZ" -> setTool(2)
            "KeyW" -> setTool(3)
            "KeyC" -> setTool(4)
            "KeyQ" -> setTool(5)
            "KeyG" -> setTool(6)
            "KeyT" -> if (sel != null) openControls("blend") else setTool(7)
            "KeyP" -> openControls("move")
            "KeyR" -> openControls("rotate")
            "KeyS" -> openControls("scale")
            "KeyK" -> jumpKey(true)
            "KeyJ" -> jumpKey(false)
            "Space", "Numpad0" -> if (playing) stopPlay() else startPlay()
            "Home" -> { stopPlay(); setTime(0f) }
            "End" -> { stopPlay(); setTime(p.duration) }
            "PageUp" -> { stopPlay(); setTime(p.time - (if (shift) 10 else 1) / 30f) }
            "PageDown" -> { stopPlay(); setTime(p.time + (if (shift) 10 else 1) / 30f) }
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown" -> nudge(code, shift)
            "Delete", "Backspace" -> deleteLayer()
            "Escape" -> { p.sel = null; refreshProps(); editor.invalidate() }
            "BracketLeft" -> setIn()
            "BracketRight" -> setOut()
            "F1" -> showHelp()
            "Equal", "NumpadAdd" -> timeline.zoomBy(1.3f)
            "Minus", "NumpadSubtract" -> timeline.zoomBy(0.77f)
        }
    }

    private fun nudge(code: String, shift: Boolean) {
        val l = p.sel
        if (l == null || l.locked) {
            if (code == "ArrowLeft") { stopPlay(); setTime(p.time - 1f / 30f) }
            if (code == "ArrowRight") { stopPlay(); setTime(p.time + 1f / 30f) }
            return
        }
        val s = if (shift) 10f else 1f
        when (code) {
            "ArrowLeft" -> l.set(P_X, p.time, l.eval(P_X, p.time) - s, p.easeIdx)
            "ArrowRight" -> l.set(P_X, p.time, l.eval(P_X, p.time) + s, p.easeIdx)
            "ArrowUp" -> l.set(P_Y, p.time, l.eval(P_Y, p.time) - s, p.easeIdx)
            "ArrowDown" -> l.set(P_Y, p.time, l.eval(P_Y, p.time) + s, p.easeIdx)
        }
        editor.invalidate()
        panel.refresh()
        commit()
    }

    private fun jumpKey(next: Boolean) {
        val l = p.sel ?: return
        var best = -1f
        for (ks in l.keys) for (k in ks) {
            if (next && k.t > p.time + 0.01f && (best < 0f || k.t < best)) best = k.t
            if (!next && k.t < p.time - 0.01f && (best < 0f || k.t > best)) best = k.t
        }
        if (best >= 0f) {
            stopPlay()
            setTime(best)
        }
    }

    private fun setIn() {
        val l = p.sel ?: return
        if (l.locked) return
        val ns = p.time.coerceIn(0f, l.end - 0.2f)
        if (l.type == T_VIDEO) l.trim += (ns - l.start) * l.speed
        l.start = ns
        timeline.invalidate()
        editor.invalidate()
        commit()
    }

    private fun setOut() {
        val l = p.sel ?: return
        if (l.locked) return
        l.end = p.time.coerceIn(l.start + 0.2f, p.duration)
        timeline.invalidate()
        editor.invalidate()
        commit()
    }

    private fun keyToggle(props: IntArray) {
        val l = p.sel ?: run { toast("Select a layer first"); return }
        val has = props.all { l.keyAt(it, p.time) != null }
        for (q in props) {
            if (has) l.removeKey(q, p.time) else l.upsert(q, p.time, l.eval(q, p.time), p.easeIdx)
        }
        timeline.invalidate()
        panel.refresh()
        commit()
    }

    /* ---------------- history / save ---------------- */

    override fun commit() {
        val s = Store.toJson(p)
        if (hi >= 0 && hi < hist.size && hist[hi] == s) return
        while (hist.size > hi + 1) hist.removeAt(hist.size - 1)
        hist.add(s)
        if (hist.size > 60) hist.removeAt(0)
        hi = hist.size - 1
        Store.saveRaw(p.id, s)
    }

    private fun restoreFrom(s: String) {
        val q = Store.fromJson(s)
        val selId = p.sel?.id
        p.copyFrom(q)
        p.sel = p.layers.firstOrNull { it.id == selId }
        p.time = p.time.coerceIn(0f, p.duration)
        Store.saveRaw(p.id, s)
        afterStructure()
        setTime(p.time)
    }

    private fun undo() {
        if (hi > 0) { hi--; restoreFrom(hist[hi]) } else toast("Nothing to undo")
    }

    private fun redo() {
        if (hi < hist.size - 1) { hi++; restoreFrom(hist[hi]) } else toast("Nothing to redo")
    }

    /** Sizes the GPU preview to the project's aspect ratio, centred in the viewport (same maths as the overlay). */
    private fun layoutGl() {
        if (!::stage.isInitialized || !::gl.isInitialized) return
        val w = stage.width
        val h = stage.height
        if (w <= 0 || h <= 0) return
        val m = 16f
        val sc = min((w - 2 * m) / p.W, (h - 2 * m) / p.H)
        val gw = (p.W * sc).toInt().coerceAtLeast(2)
        val gh = (p.H * sc).toInt().coerceAtLeast(2)
        if (gw == glW && gh == glH) return
        glW = gw
        glH = gh
        gl.layoutParams = FrameLayout.LayoutParams(gw, gh, Gravity.CENTER)
    }

    private fun afterStructure() {
        layoutGl()
        timeline.invalidate()
        editor.invalidate()
        refreshProps()
        updateLabels()
    }

    private fun updateLabels() {
        if (!::compTitle.isInitialized) return
        compTitle.text = "COMPOSITION: " + p.name.uppercase() + " (PREVIEW)"
        resLabel.text = "${p.W} x ${p.H}"
        timeLabel.text = fmtTime(p.time)
    }

    /* ---------------- adding / editing layers ---------------- */

    private fun defaultStart(): Float = p.time.coerceAtMost(p.duration - 0.5f).coerceAtLeast(0f)

    private fun finishAdd(l: Layer) {
        p.layers.add(l)
        p.sel = l
        afterStructure()
        commit()
    }

    private fun addShape(kind: Int) = addShapeAt(p.W / 2f, p.H / 2f, kind)

    private fun addShapeAt(x: Float, y: Float, kind: Int = 0) {
        val l = p.newLayer(T_SHAPE)
        l.shape = kind
        l.name = Renderer.shapeNames[kind]
        when (kind) {
            7 -> { l.w = 520f; l.h = 18f }
            0 -> { l.w = 340f; l.h = 340f; l.radius = 48f }
            else -> { l.w = 340f; l.h = 340f }
        }
        l.base[P_X] = x
        l.base[P_Y] = y
        l.start = defaultStart()
        finishAdd(l)
    }

    private fun addText(text: String, size: Float) = addTextAt(p.W / 2f, p.H / 2f, text, size)

    private fun addTextAt(x: Float, y: Float, text: String = "Text", size: Float = 96f) {
        val l = p.newLayer(T_TEXT)
        l.text = text
        l.size = size
        l.base[P_X] = x
        l.base[P_Y] = y
        l.start = defaultStart()
        finishAdd(l)
    }

    private fun addCamera() {
        if (p.layers.any { it.type == T_CAMERA }) {
            toast("This composition already has a camera")
            return
        }
        val l = p.newLayer(T_CAMERA)
        l.start = 0f
        l.end = p.duration
        l.w = 10f
        l.h = 10f
        finishAdd(l)
        toast("Camera added. Turn on 3D for the layers it should affect (tool C orbits it)")
    }

    private fun addParticles() {
        val l = p.newLayer(T_PARTICLES)
        l.start = defaultStart()
        finishAdd(l)
    }

    private fun pickAny() = pick("*/*", 23)

    @Suppress("DEPRECATION")
    private fun pick(type: String, code: Int) {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = type
        i.addCategory(Intent.CATEGORY_OPENABLE)
        if (type == "*/*") i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        startActivityForResult(Intent.createChooser(i, "Choose media"), code)
    }

    @Suppress("DEPRECATION")
    private fun importFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 31)
    }

    private fun loadFolders() {
        val folders = ArrayList(p.folders)
        Thread {
            val all = ArrayList<DocItem>()
            for (f in folders) {
                try {
                    all.addAll(FolderScan.list(applicationContext, Uri.parse(f)))
                } catch (e: Exception) {
                    // the folder was moved or the permission was removed
                }
            }
            runOnUiThread { projectPanel.setItems(all) }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 31) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            if (!p.folders.contains(uri.toString())) p.folders.add(uri.toString())
            Store.save(p)
            loadFolders()
            return
        }
        if (requestCode < 21 || requestCode > 23) return
        toast("Importing...")
        Thread {
            val name = Store.importMedia(applicationContext, p.id, uri)
            runOnUiThread {
                if (name == null) toast("Could not import that file") else addMedia(name, defaultStart())
            }
        }.start()
    }

    private fun addMedia(name: String, startAt: Float) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isVideo = ext in setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
        val path = Store.mediaPath(p.id, name)
        val l = p.newLayer(if (isVideo) T_VIDEO else T_IMAGE)
        l.media = name
        val mw: Int
        val mh: Int
        val start = startAt.coerceIn(0f, max(0f, p.duration - 0.5f))
        if (isVideo) {
            val info = MediaCache.videoInfo(path)
            if (info == null || info.dur <= 0f) { toast("Could not read that video"); return }
            mw = info.w
            mh = info.h
            l.srcDur = info.dur
            l.start = start
            if (start + info.dur > p.duration) p.duration = min(300f, ceil(start + info.dur))
            l.end = min(p.duration, start + info.dur)
        } else {
            val bmp = MediaCache.image(path)
            if (bmp == null) { toast("Could not read that image"); return }
            mw = bmp.width
            mh = bmp.height
            l.start = start
        }
        val fit = minOf(1f, p.W * 0.9f / mw, p.H * 0.6f / mh)
        l.w = mw * fit
        l.h = mh * fit
        finishAdd(l)
        setTime(p.time)
    }

    private fun editSel(block: (Layer) -> Unit) {
        val l = p.sel ?: run { toast("Select a layer first"); return }
        block(l)
        editor.invalidate()
        timeline.invalidate()
        panel.refresh()
        commit()
    }

    private fun panelOpenOrToast(name: String) {
        if (p.sel == null) toast("Select a layer first") else panel.open(name)
    }

    private fun duplicate() {
        val l = p.sel ?: return toast("Select a layer first")
        val c = l.duplicate(p.nextLayerId())
        p.layers.add(p.layers.indexOf(l) + 1, c)
        p.sel = c
        afterStructure()
        commit()
    }

    private fun copyLayer() {
        val l = p.sel ?: return toast("Select a layer first")
        clip = l.duplicate(0)
        toast("Layer copied")
    }

    private fun pasteLayer() {
        val c = clip ?: return toast("Nothing copied yet")
        val n = c.duplicate(p.nextLayerId())
        n.name = c.name
        p.layers.add(n)
        p.sel = n
        afterStructure()
        commit()
    }

    private fun pasteStyle() {
        val c = clip
        val l = p.sel
        if (c == null || l == null) {
            toast("Copy a layer, then select another one")
            return
        }
        l.copyStyleFrom(c)
        editor.invalidate()
        commit()
    }

    private fun deleteLayer() {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.locked) return toast("Layer is locked")
        p.layers.remove(l)
        p.sel = null
        afterStructure()
        commit()
    }

    private fun fitMode(mode: Int) {
        val l = p.sel ?: return toast("Select a layer first")
        val sz = Renderer.sizeOf(l)
        val sx = p.W / sz[0]
        val sy = p.H / sz[1]
        if (mode == 2 && l.type != T_TEXT) {
            l.w = p.W.toFloat()
            l.h = p.H.toFloat()
            l.set(P_SCALE, p.time, 1f, p.easeIdx)
        } else {
            l.set(P_SCALE, p.time, if (mode == 1) max(sx, sy) else min(sx, sy), p.easeIdx)
        }
        l.set(P_X, p.time, p.W / 2f, p.easeIdx)
        l.set(P_Y, p.time, p.H / 2f, p.easeIdx)
        editor.invalidate()
        panel.refresh()
        commit()
    }

    private fun tagDialog() {
        val l = p.sel ?: return toast("Select a layer first")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(12), dp(12), dp(12), dp(4))
        val dlg = AlertDialog.Builder(this).setTitle("Colour tag").setView(row).setNegativeButton("Close", null).create()
        for (i in TAG_COLORS.indices) {
            val v = View(this)
            val g = GradientDrawable()
            g.setColor(if (i == 0) 0xFF3A3F55.toInt() else TAG_COLORS[i])
            g.cornerRadius = dp(8).toFloat()
            g.setStroke(dp(if (l.tag == i) 3 else 1), if (l.tag == i) Color.WHITE else 0xFF666666.toInt())
            v.background = g
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
            lp.setMargins(dp(4), 0, dp(4), 0)
            v.setOnClickListener {
                l.tag = i
                timeline.invalidate()
                commit()
                dlg.dismiss()
            }
            row.addView(v, lp)
        }
        dlg.show()
    }

    private fun cycleBlur() {
        p.shutter = if (p.shutter <= 0f) 180f else if (p.shutter <= 180f) 360f else 0f
        toast(if (p.shutter <= 0f) "Motion blur off" else "Motion blur shutter " + p.shutter.toInt() + "\u00B0")
        editor.invalidate()
        commit()
    }

    private fun settings() {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(16), dp(8), dp(16), dp(4))
        val ratioBtn = btn("Canvas size: ${p.ratio}") {}
        ratioBtn.setOnClickListener {
            val idx = Project.RATIOS.indexOfFirst { it.first == p.ratio }
            p.changeRatio(Project.RATIOS[(idx + 1) % Project.RATIOS.size].first)
            ratioBtn.text = "Canvas size: ${p.ratio}"
            layoutGl()
            editor.invalidate()
            updateLabels()
            commit()
        }
        val durBtn = btn("Duration: ${p.duration.toInt()}s  (tap to add 1s)") {}
        durBtn.setOnClickListener {
            p.duration = if (p.duration >= 60f) 2f else p.duration + 1f
            durBtn.text = "Duration: ${p.duration.toInt()}s  (tap to add 1s)"
            for (l in p.layers) if (l.end > p.duration) l.end = p.duration
            setTime(p.time)
            commit()
        }
        val bgBtn = btn("Background colour (tap to change)") {}
        bgBtn.setOnClickListener {
            val i = bgPalette.indexOf(p.bg)
            p.bg = bgPalette[(i + 1) % bgPalette.size]
            editor.invalidate()
            commit()
        }
        fun blurLabel() = when {
            p.shutter <= 0f -> "Motion blur: Off"
            p.shutter <= 180f -> "Motion blur: Normal (180\u00B0)"
            else -> "Motion blur: Strong (360\u00B0)"
        }
        val blurBtn = btn(blurLabel()) {}
        blurBtn.setOnClickListener {
            p.shutter = if (p.shutter <= 0f) 180f else if (p.shutter <= 180f) 360f else 0f
            blurBtn.text = blurLabel()
            editor.invalidate()
            commit()
        }
        for (b in arrayOf(ratioBtn, durBtn, bgBtn, blurBtn)) {
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }
            ll.addView(b)
        }
        AlertDialog.Builder(this)
            .setTitle("Composition settings")
            .setView(ll)
            .setPositiveButton("Done", null)
            .show()
    }

    /* ---------------- time / playback ---------------- */

    private fun setTime(t: Float, light: Boolean = false) {
        p.time = t.coerceIn(0f, p.duration)
        editor.invalidate()
        timeline.invalidate()
        timeLabel.text = fmtTime(p.time)
        if (!light) panel.refresh()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = System.nanoTime()
            var t = p.time + (now - lastNs) / 1e9f
            lastNs = now
            if (t >= p.duration) t = 0f
            setTime(t, true)
            timeline.follow()
            handler.postDelayed(this, 16)
        }
    }

    private fun startPlay() {
        if (playing) return
        playing = true
        playIcon.kind = IconView.PAUSE
        playIcon.invalidate()
        lastNs = System.nanoTime()
        handler.post(tick)
    }

    private fun stopPlay() {
        playing = false
        handler.removeCallbacks(tick)
        if (::playIcon.isInitialized) {
            playIcon.kind = IconView.PLAY
            playIcon.invalidate()
        }
        if (::panel.isInitialized) panel.refresh()
    }

    /* ---------------- export ---------------- */

    private fun doExport() {
        stopPlay()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Rendering video")
            .setMessage("0%")
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            var error: String? = null
            val tmp = File(cacheDir, "export.mp4")
            try {
                Exporter.export(p, tmp, 30) { pct ->
                    runOnUiThread { dlg.setMessage("$pct%") }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            val where = if (error == null) saveToGallery(tmp) else null
            runOnUiThread {
                dlg.dismiss()
                if (error != null) toast("Export failed: $error")
                else toast(if (where != null) "Saved to $where" else "Could not save the video")
            }
        }.start()
    }

    private fun saveToGallery(src: File): String? {
        return try {
            val name = "motion-${System.currentTimeMillis()}.mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MotionStudio")
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(src).use { it.copyTo(out) }
                }
                "Movies/MotionStudio"
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
                dir.mkdirs()
                val dst = File(dir, name)
                src.copyTo(dst, true)
                dst.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    /* ---------------- lifecycle ---------------- */

    override fun onBackPressed() {
        if (!::panel.isInitialized) { super.onBackPressed(); return }
        if (ctrlBody.visibility == View.VISIBLE) {
            if (panel.back()) return
            showTimelineTab()
            return
        }
        if (p.sel != null) { p.sel = null; refreshProps(); editor.invalidate(); return }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::gl.isInitialized) gl.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::gl.isInitialized) gl.onPause()
        if (!::p.isInitialized || !::playIcon.isInitialized) return
        stopPlay()
        Store.save(p)
    }

    override fun onStop() {
        super.onStop()
        if (!::p.isInitialized) return
        Store.saveThumb(p)
    }

    override fun onDestroy() {
        super.onDestroy()
        VideoCache.release()
    }
}
