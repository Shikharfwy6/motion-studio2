package com.motionstudio.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import java.io.File
import kotlin.math.max

object Exporter {
    /**
     * Renders the project with the same GPU compositor as the preview straight into the
     * hardware H.264 encoder. Videos are decoded by the hardware decoder, so this is fast.
     * Call from a background thread. No audio yet.
     */
    fun export(p: Project, out: File, fps: Int = 30, onProgress: (Int) -> Unit) {
        val w = p.W - p.W % 2
        val h = p.H - p.H % 2
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var started = false

        // EGL context that renders into the encoder surface
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0)
        val cfg = cfgs[0] ?: throw RuntimeException("No suitable EGL config")
        val ctx = EGL14.eglCreateContext(
            dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val surf = EGL14.eglCreateWindowSurface(dpy, cfg, inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val scene = GLScene()
        scene.init()

        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started && buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        try {
            val total = max(1, (p.duration * fps).toInt())
            for (i in 0 until total) {
                scene.draw(p, i / fps.toFloat(), w, h)
                EGLExt.eglPresentationTimeANDROID(dpy, surf, i * 1_000_000_000L / fps)
                EGL14.eglSwapBuffers(dpy, surf)
                drain(false)
                onProgress((i + 1) * 100 / total)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try { scene.release() } catch (e: Exception) {}
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(dpy)
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            inputSurface.release()
            try { if (started) muxer.stop() } catch (e: Exception) {}
            muxer.release()
        }
    }
}
