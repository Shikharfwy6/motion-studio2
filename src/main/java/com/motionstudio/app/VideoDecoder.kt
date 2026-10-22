package com.motionstudio.app

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Hardware video decoder that renders straight into a GPU texture (SurfaceTexture / external OES).
 * No frame is ever copied through the CPU. Must be used on a thread that has a GL context current.
 */
class VideoDecoder(private val path: String) {
    var texId = 0
        private set
    val texMatrix = FloatArray(16)

    private var st: SurfaceTexture? = null
    private var surface: Surface? = null
    private var ex: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private val sem = Semaphore(0)
    private var inputDone = false
    private var outputEos = false
    private var lastPts = 0L
    private var hasFrame = false
    private var failed = false
    private var frameUs = 33_000L

    init {
        Matrix.setIdentityM(texMatrix, 0)
    }

    private fun open(): Boolean {
        try {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            GLES20.glBindTexture(target, texId)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val s = SurfaceTexture(texId)
            st = s
            s.setOnFrameAvailableListener({ sem.release() }, Handler(Looper.getMainLooper()))
            val sf = Surface(s)
            surface = sf

            val e = MediaExtractor()
            e.setDataSource(path)
            ex = e
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) return false
            e.selectTrack(track)
            try {
                if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val fr = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
                    if (fr > 0) frameUs = 1_000_000L / fr
                }
            } catch (x: Exception) {
            }
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, sf, null, 0)
            c.start()
            return true
        } catch (x: Throwable) {
            return false
        }
    }

    private fun feedInput() {
        val c = codec ?: return
        val e = ex ?: return
        if (inputDone) return
        val ib = c.dequeueInputBuffer(0)
        if (ib < 0) return
        val buf = c.getInputBuffer(ib)
        val n = if (buf == null) -1 else e.readSampleData(buf, 0)
        if (n < 0) {
            c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            c.queueInputBuffer(ib, 0, n, e.sampleTime, 0)
            e.advance()
        }
    }

    private fun seekTo(us: Long) {
        val c = codec ?: return
        val e = ex ?: return
        e.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        c.flush()
        inputDone = false
        outputEos = false
        hasFrame = false
    }

    /**
     * Makes the texture show the frame for source time [us]. While playing this just advances by
     * one frame, so it is cheap. Returns false when no frame is available.
     */
    fun frameAt(us: Long): Boolean {
        if (failed) return false
        if (codec == null) {
            if (!open()) {
                failed = true
                return false
            }
        }
        val c = codec ?: return false
        val s = st ?: return false
        val target = if (us < 0) 0L else us

        if (hasFrame) {
            val d = target - lastPts
            if (d >= -20_000L && d < frameUs + 20_000L) return true
            if (d < -20_000L || d > 1_500_000L) seekTo(target)
        } else if (target > 500_000L) {
            seekTo(target)
        }
        if (outputEos && hasFrame) return true

        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            feedInput()
            val ob = c.dequeueOutputBuffer(info, 10_000)
            if (ob < 0) continue
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = info.presentationTimeUs
            if (info.size > 0 && pts >= target - 20_000L) {
                sem.drainPermits()
                c.releaseOutputBuffer(ob, true)
                if (sem.tryAcquire(300, TimeUnit.MILLISECONDS)) {
                    s.updateTexImage()
                    s.getTransformMatrix(texMatrix)
                    lastPts = pts
                    hasFrame = true
                    return true
                }
                return hasFrame
            }
            c.releaseOutputBuffer(ob, false)
            if (eos) {
                outputEos = true
                return hasFrame
            }
        }
        return hasFrame
    }

    fun release() {
        try { codec?.stop() } catch (x: Exception) {}
        try { codec?.release() } catch (x: Exception) {}
        try { ex?.release() } catch (x: Exception) {}
        try { surface?.release() } catch (x: Exception) {}
        try { st?.release() } catch (x: Exception) {}
        if (texId != 0) {
            try { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) } catch (x: Exception) {}
            texId = 0
        }
        codec = null
    }
}
