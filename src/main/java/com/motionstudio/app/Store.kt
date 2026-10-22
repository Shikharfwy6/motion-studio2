package com.motionstudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min

object Store {
    private lateinit var root: File

    class Meta(
        val id: String, val name: String, val ratio: String,
        val w: Int, val h: Int, val dur: Float, val modified: Long
    )

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "projects")
        root.mkdirs()
    }

    fun mediaDir(id: String): File {
        val d = File(root, "media_$id")
        d.mkdirs()
        return d
    }

    fun mediaPath(id: String, name: String): String = File(File(root, "media_$id"), name).absolutePath
    fun thumbFile(id: String) = File(root, "$id.png")
    private fun jsonFile(id: String) = File(root, "$id.json")

    fun toJson(p: Project): String {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        o.put("ratio", p.ratio)
        o.put("W", p.W)
        o.put("H", p.H)
        o.put("duration", p.duration.toDouble())
        o.put("bg", p.bg)
        o.put("ease", p.easeIdx)
        o.put("nextId", p.nextId)
        o.put("shutter", p.shutter.toDouble())
        val fa = JSONArray()
        for (x in p.folders) fa.put(x)
        o.put("folders", fa)
        val arr = JSONArray()
        for (l in p.layers) {
            val j = JSONObject()
            j.put("id", l.id)
            j.put("type", l.type)
            j.put("name", l.name)
            j.put("text", l.text)
            j.put("size", l.size.toDouble())
            j.put("color", l.color)
            j.put("w", l.w.toDouble())
            j.put("h", l.h.toDouble())
            j.put("radius", l.radius.toDouble())
            j.put("shape", l.shape)
            j.put("start", l.start.toDouble())
            j.put("end", l.end.toDouble())
            j.put("trim", l.trim.toDouble())
            j.put("srcDur", l.srcDur.toDouble())
            j.put("media", l.media ?: "")
            j.put("fillType", l.fillType)
            j.put("color2", l.color2)
            j.put("gradType", l.gradType)
            j.put("gradAngle", l.gradAngle.toDouble())
            j.put("strokeW", l.strokeW.toDouble())
            j.put("strokeColor", l.strokeColor)
            j.put("shadowOn", l.shadowOn)
            j.put("shadowBlur", l.shadowBlur.toDouble())
            j.put("shadowDx", l.shadowDx.toDouble())
            j.put("shadowDy", l.shadowDy.toDouble())
            j.put("shadowColor", l.shadowColor)
            j.put("blend", l.blend)
            j.put("hidden", l.hidden)
            j.put("flipH", l.flipH)
            j.put("flipV", l.flipV)
            j.put("skewX", l.skewX.toDouble())
            j.put("skewY", l.skewY.toDouble())
            j.put("speed", l.speed.toDouble())
            j.put("tag", l.tag)
            j.put("align", l.align)
            j.put("fontIdx", l.fontIdx)
            j.put("muted", l.muted)
            j.put("threeD", l.threeD)
            j.put("locked", l.locked)
            j.put("wigFreq", l.wigFreq.toDouble())
            j.put("wigSeed", l.wigSeed.toDouble())
            j.put("loopMode", l.loopMode)
            val wa = JSONArray()
            for (v in l.wigAmp) wa.put(v.toDouble())
            j.put("wigAmp", wa)
            j.put("pCount", l.pCount)
            j.put("pLife", l.pLife.toDouble())
            j.put("pSpeed", l.pSpeed.toDouble())
            j.put("pSize", l.pSize.toDouble())
            j.put("pGravity", l.pGravity.toDouble())
            j.put("pSpread", l.pSpread.toDouble())
            j.put("pAngle", l.pAngle.toDouble())
            val b = JSONArray()
            for (v in l.base) b.put(v.toDouble())
            j.put("base", b)
            val ks = JSONArray()
            for (i in 0 until P_COUNT) {
                val a = JSONArray()
                for (k in l.keys[i]) {
                    val e = JSONArray()
                    e.put(k.t.toDouble())
                    e.put(k.v.toDouble())
                    e.put(k.ease)
                    a.put(e)
                }
                ks.put(a)
            }
            j.put("keys", ks)
            arr.put(j)
        }
        o.put("layers", arr)
        return o.toString()
    }

    fun fromJson(s: String): Project {
        val o = JSONObject(s)
        val p = Project(o.getString("id"))
        p.name = o.optString("name", "Untitled")
        p.ratio = o.optString("ratio", "9:16")
        p.W = o.optInt("W", 720)
        p.H = o.optInt("H", 1280)
        p.duration = o.optDouble("duration", 5.0).toFloat()
        p.bg = o.optInt("bg", p.bg)
        p.easeIdx = o.optInt("ease", 2)
        p.shutter = o.optDouble("shutter", 0.0).toFloat()
        val fa = o.optJSONArray("folders")
        if (fa != null) for (i in 0 until fa.length()) p.folders.add(fa.getString(i))
        val arr = o.optJSONArray("layers") ?: JSONArray()
        var maxId = 0
        for (n in 0 until arr.length()) {
            val j = arr.getJSONObject(n)
            val l = Layer(j.getInt("id"), j.getInt("type"), j.optString("name", "Layer"))
            l.text = j.optString("text", "")
            l.size = j.optDouble("size", 96.0).toFloat()
            l.color = j.optInt("color", -1)
            l.w = j.optDouble("w", 400.0).toFloat()
            l.h = j.optDouble("h", 300.0).toFloat()
            l.radius = j.optDouble("radius", 28.0).toFloat()
            l.shape = j.optInt("shape", 0)
            l.start = j.optDouble("start", 0.0).toFloat()
            l.end = j.optDouble("end", 5.0).toFloat()
            l.trim = j.optDouble("trim", 0.0).toFloat()
            l.srcDur = j.optDouble("srcDur", 0.0).toFloat()
            val m = j.optString("media", "")
            l.media = if (m.isEmpty()) null else m
            l.fillType = j.optInt("fillType", 1)
            l.color2 = j.optInt("color2", -1)
            l.gradType = j.optInt("gradType", 0)
            l.gradAngle = j.optDouble("gradAngle", 0.0).toFloat()
            l.strokeW = j.optDouble("strokeW", 0.0).toFloat()
            l.strokeColor = j.optInt("strokeColor", 0xFF000000.toInt())
            l.shadowOn = j.optBoolean("shadowOn", false)
            l.shadowBlur = j.optDouble("shadowBlur", 12.0).toFloat()
            l.shadowDx = j.optDouble("shadowDx", 6.0).toFloat()
            l.shadowDy = j.optDouble("shadowDy", 6.0).toFloat()
            l.shadowColor = j.optInt("shadowColor", 0x99000000.toInt())
            l.blend = j.optInt("blend", 0)
            l.hidden = j.optBoolean("hidden", false)
            l.flipH = j.optBoolean("flipH", false)
            l.flipV = j.optBoolean("flipV", false)
            l.skewX = j.optDouble("skewX", 0.0).toFloat()
            l.skewY = j.optDouble("skewY", 0.0).toFloat()
            l.speed = j.optDouble("speed", 1.0).toFloat()
            l.tag = j.optInt("tag", 0)
            l.align = j.optInt("align", 1)
            l.fontIdx = j.optInt("fontIdx", 0)
            l.muted = j.optBoolean("muted", false)
            l.threeD = j.optBoolean("threeD", false)
            l.locked = j.optBoolean("locked", false)
            l.wigFreq = j.optDouble("wigFreq", 3.0).toFloat()
            l.wigSeed = j.optDouble("wigSeed", 0.0).toFloat()
            l.loopMode = j.optInt("loopMode", 0)
            val wa = j.optJSONArray("wigAmp")
            if (wa != null) for (i in 0 until min(P_COUNT, wa.length())) l.wigAmp[i] = wa.getDouble(i).toFloat()
            l.pCount = j.optInt("pCount", 80)
            l.pLife = j.optDouble("pLife", 2.0).toFloat()
            l.pSpeed = j.optDouble("pSpeed", 300.0).toFloat()
            l.pSize = j.optDouble("pSize", 40.0).toFloat()
            l.pGravity = j.optDouble("pGravity", 200.0).toFloat()
            l.pSpread = j.optDouble("pSpread", 360.0).toFloat()
            l.pAngle = j.optDouble("pAngle", -90.0).toFloat()
            val b = j.optJSONArray("base")
            if (b != null) for (i in 0 until min(P_COUNT, b.length())) l.base[i] = b.getDouble(i).toFloat()
            val ks = j.optJSONArray("keys")
            if (ks != null) {
                for (i in 0 until min(P_COUNT, ks.length())) {
                    val a = ks.getJSONArray(i)
                    for (q in 0 until a.length()) {
                        val e = a.getJSONArray(q)
                        l.keys[i].add(Key(e.getDouble(0).toFloat(), e.getDouble(1).toFloat(), e.getInt(2)))
                    }
                }
            }
            if (l.id > maxId) maxId = l.id
            p.layers.add(l)
        }
        p.nextId = maxOf(o.optInt("nextId", 1), maxId + 1)
        return p
    }

    fun saveRaw(id: String, json: String) {
        try { jsonFile(id).writeText(json) } catch (e: Exception) {}
    }

    fun save(p: Project) = saveRaw(p.id, toJson(p))

    fun load(id: String): Project? = try { fromJson(jsonFile(id).readText()) } catch (e: Exception) { null }

    fun create(name: String, ratio: String): Project {
        val p = Project()
        p.name = name
        val r = Project.RATIOS.firstOrNull { it.first == ratio } ?: Project.RATIOS[0]
        p.ratio = r.first
        p.W = r.second
        p.H = r.third
        save(p)
        return p
    }

    fun list(): List<Meta> {
        val out = ArrayList<Meta>()
        val files = root.listFiles()?.filter { it.name.endsWith(".json") } ?: return out
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                out.add(Meta(
                    o.getString("id"), o.optString("name", "Untitled"), o.optString("ratio", "9:16"),
                    o.optInt("W", 720), o.optInt("H", 1280), o.optDouble("duration", 5.0).toFloat(), f.lastModified()
                ))
            } catch (e: Exception) {}
        }
        out.sortByDescending { it.modified }
        return out
    }

    fun delete(id: String) {
        jsonFile(id).delete()
        thumbFile(id).delete()
        File(root, "media_$id").deleteRecursively()
    }

    fun duplicate(id: String): String? {
        val src = load(id) ?: return null
        val nid = UUID.randomUUID().toString().replace("-", "").take(12)
        val md = File(root, "media_$id")
        if (md.exists()) md.copyRecursively(File(root, "media_$nid"), true)
        val th = thumbFile(id)
        if (th.exists()) th.copyTo(thumbFile(nid), true)
        src.id = nid
        src.name = src.name + " copy"
        save(src)
        return nid
    }

    fun rename(id: String, name: String) {
        val p = load(id) ?: return
        p.name = name
        save(p)
    }

    fun saveThumb(p: Project) {
        try {
            val maxD = 360f
            val sc = min(maxD / p.W, maxD / p.H)
            val bw = (p.W * sc).toInt().coerceAtLeast(2)
            val bh = (p.H * sc).toInt().coerceAtLeast(2)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(sc, sc)
            Renderer.draw(c, p, min(1f, p.duration), true)
            for (b in Renderer.toRecycle) b.recycle()
            Renderer.toRecycle.clear()
            FileOutputStream(thumbFile(p.id)).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
        } catch (e: Exception) {}
    }

    fun importMedia(ctx: Context, pid: String, uri: Uri): String? {
        return try {
            val type = ctx.contentResolver.getType(uri) ?: ""
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
                ?: if (type.startsWith("video")) "mp4" else "jpg"
            val name = "m${System.currentTimeMillis()}.$ext"
            val out = File(mediaDir(pid), name)
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
            name
        } catch (e: Exception) {
            null
        }
    }
}
