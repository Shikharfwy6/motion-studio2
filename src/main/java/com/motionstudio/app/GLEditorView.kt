package com.motionstudio.app

import android.content.Context
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Live preview. Draws the project with OpenGL on its own render thread. */
class GLEditorView(context: Context, private val p: Project) : GLSurfaceView(context) {
    private val scene = GLScene()
    private var vw = 1
    private var vh = 1

    init {
        setEGLContextClientVersion(2)
        setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                scene.release()
                scene.init()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                vw = width
                vh = height
            }

            override fun onDrawFrame(gl: GL10?) {
                scene.draw(p, p.time, vw, vh)
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onPause() {
        queueEvent { scene.release() }
        super.onPause()
    }
}
