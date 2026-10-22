package com.motionstudio.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

class HomeActivity : Activity() {
    private val BG = 0xFFF4F5F8.toInt()
    private val INK = 0xFF1A1C2B.toInt()
    private val GREY = 0xFF8A8FA3.toInt()

    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "Motion Studio"
        title.setTextColor(INK)
        title.textSize = 24f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setPadding(dp(20), dp(22), dp(20), dp(6))
        col.addView(title)

        val heading = TextView(this)
        heading.text = "Your projects"
        heading.setTextColor(INK)
        heading.textSize = 17f
        heading.setTypeface(null, android.graphics.Typeface.BOLD)
        heading.setPadding(dp(20), dp(14), dp(20), dp(8))
        col.addView(heading)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(16), 0, dp(16), dp(120))
        val sv = ScrollView(this)
        sv.addView(list)
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val fab = IconView(this, IconView.PLUS)
        fab.tint = 0xFF06281C.toInt()
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(C_GREEN)
        fab.background = oval
        fab.elevation = dp(8).toFloat()
        fab.setPadding(dp(16), dp(16), dp(16), dp(16))
        fab.setOnClickListener { newProjectDialog() }
        val lp = FrameLayout.LayoutParams(dp(68), dp(68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(28)
        root.addView(fab, lp)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val items = Store.list()
        if (items.isEmpty()) {
            val t = TextView(this)
            t.text = "No projects yet.\nTap the green + button to create your first one."
            t.setTextColor(GREY)
            t.textSize = 15f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(20), dp(80), dp(20), 0)
            list.addView(t)
            return
        }
        for (m in items) list.addView(card(m))
    }

    private fun small(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(GREY)
        t.textSize = 13f
        t.setPadding(0, dp(3), dp(14), 0)
        return t
    }

    private fun card(m: Store.Meta): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = round(Color.WHITE, 16)
        row.setPadding(dp(12), dp(12), dp(8), dp(12))
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.bottomMargin = dp(12)
        row.layoutParams = rlp

        val tf = FrameLayout(this)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.background = round(0xFFD9DBE2.toInt(), 10)
        val f = Store.thumbFile(m.id)
        if (f.exists()) iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
        tf.addView(iv, FrameLayout.LayoutParams(dp(96), dp(96)))
        val dur = TextView(this)
        dur.text = fmtTime(m.dur)
        dur.setTextColor(Color.WHITE)
        dur.textSize = 11f
        dur.background = round(0xCC1A1C2B.toInt(), 6)
        dur.setPadding(dp(6), dp(2), dp(6), dp(2))
        val dlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        dlp.setMargins(dp(6), 0, 0, dp(6))
        tf.addView(dur, dlp)
        row.addView(tf, LinearLayout.LayoutParams(dp(96), dp(96)))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(dp(14), 0, dp(6), 0)
        val name = TextView(this)
        name.text = m.name
        name.setTextColor(INK)
        name.textSize = 16f
        name.setTypeface(null, android.graphics.Typeface.BOLD)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        info.addView(name)
        val l1 = LinearLayout(this)
        l1.orientation = LinearLayout.HORIZONTAL
        l1.addView(small(m.ratio))
        l1.addView(small("${minOf(m.w, m.h)}p"))
        val l2 = LinearLayout(this)
        l2.orientation = LinearLayout.HORIZONTAL
        l2.addView(small("${m.w}x${m.h}"))
        l2.addView(small("30fps"))
        info.addView(l1)
        info.addView(l2)
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val more = IconView(this, IconView.DOTS)
        more.tint = INK
        more.setOnClickListener { v -> menu(v, m) }
        row.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))

        row.setOnClickListener { open(m.id) }
        return row
    }

    private fun menu(anchor: View, m: Store.Meta) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Rename")
        pm.menu.add("Duplicate")
        pm.menu.add("Delete")
        pm.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Rename" -> rename(m)
                "Duplicate" -> { Store.duplicate(m.id); refresh() }
                "Delete" -> AlertDialog.Builder(this)
                    .setTitle("Delete project?")
                    .setMessage("\"${m.name}\" and its media will be removed.")
                    .setPositiveButton("Delete") { _, _ -> Store.delete(m.id); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        pm.show()
    }

    private fun rename(m: Store.Meta) {
        val et = EditText(this)
        et.setText(m.name)
        et.setSelection(et.text.length)
        AlertDialog.Builder(this)
            .setTitle("Rename project")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { Store.rename(m.id, n); refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(id: String) {
        val i = Intent(this, EditorActivity::class.java)
        i.putExtra("id", id)
        startActivity(i)
    }

    private fun newProjectDialog() {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(20), dp(8), dp(20), dp(4))
        val et = EditText(this)
        et.setText("Project ${Store.list().size + 1}")
        et.setSelection(et.text.length)
        ll.addView(et)
        val hint = TextView(this)
        hint.text = "Canvas size"
        hint.setPadding(0, dp(14), 0, dp(4))
        ll.addView(hint)
        var chosen = Project.RATIOS[0].first
        val btns = ArrayList<Button>()
        val rowv = LinearLayout(this)
        rowv.orientation = LinearLayout.HORIZONTAL
        for (r in Project.RATIOS) {
            val b = Button(this)
            b.text = r.first
            b.isAllCaps = false
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(dp(8), 0, dp(8), 0)
            b.setOnClickListener {
                chosen = r.first
                for (x in btns) x.alpha = if (x === b) 1f else 0.45f
            }
            btns.add(b)
            rowv.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        for ((i, x) in btns.withIndex()) x.alpha = if (i == 0) 1f else 0.45f
        ll.addView(rowv)
        AlertDialog.Builder(this)
            .setTitle("New project")
            .setView(ll)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().trim().ifEmpty { "Untitled" }
                val p = Store.create(n, chosen)
                open(p.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
