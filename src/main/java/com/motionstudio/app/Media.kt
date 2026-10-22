package com.motionstudio.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import kotlin.math.max

class VideoInfo(val dur: Float, val w: Int, val h: Int)

object MediaCache {
    private val images = HashMap<String, Bitmap>()

    fun image(path: String): Bitmap? {
        val cached = synchronized(images) { images[path] }
        if (cached != null) return cached
        val b = decodeImage(path, 1600) ?: return null
        synchronized(images) { images[path] = b }
        return b
    }

    private fun decodeImage(path: String, maxDim: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > maxDim) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val b = BitmapFactory.decodeFile(path, o2) ?: return null
            val orient = try {
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            } catch (e: Exception) {
                1
            }
            val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            if (deg != 0f) {
                val m = Matrix()
                m.postRotate(deg)
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            } else b
        } catch (e: Exception) {
            null
        }
    }

    fun videoInfo(path: String): VideoInfo? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            r.release()
            if (rot == 90 || rot == 270) VideoInfo(d / 1000f, h, w) else VideoInfo(d / 1000f, w, h)
        } catch (e: Exception) {
            null
        }
    }
}

/** Only used for project thumbnails (the live preview and export use the GPU decoder). */
object VideoCache {
    private val lock = Any()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()

    private fun retriever(path: String): MediaMetadataRetriever = synchronized(lock) {
        retrievers.getOrPut(path) {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            r
        }
    }

    fun exactFrame(path: String, us: Long): Bitmap? {
        return try {
            val r = retriever(path)
            synchronized(r) { r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        synchronized(lock) {
            for (r in retrievers.values) try { r.release() } catch (e: Exception) {}
            retrievers.clear()
        }
    }
}
