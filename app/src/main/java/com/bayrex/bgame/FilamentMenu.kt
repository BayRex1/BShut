package com.bayrex.bgame

import android.content.Context
import android.graphics.Color
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.BufferedInputStream
import java.net.URL
import java.nio.ByteBuffer

class FilamentMenu(context: Context) {
    val surfaceView = SurfaceView(context)
    private val choreographer = Choreographer.getInstance()
    private lateinit var viewer: ModelViewer
    private var started = false
    private var loaderThread: Thread? = null
    private var animationStartNs = 0L

    companion object {
        init { Utils.init() }
        private const val MODEL_URL =
            "https://raw.githubusercontent.com/mrdoob/three.js/dev/examples/models/gltf/Soldier.glb"
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!started) return
            viewer.animator?.apply {
                if (animationCount > 0) {
                    val elapsed = (frameTimeNanos - animationStartNs) / 1_000_000_000.0f
                    applyAnimation(0, elapsed)
                    updateBoneMatrices()
                }
            }
            viewer.render(frameTimeNanos)
            choreographer.postFrameCallback(this)
        }
    }

    fun start() {
        if (started) return
        started = true
        animationStartNs = System.nanoTime()
        viewer = ModelViewer(surfaceView)

        surfaceView.setBackgroundColor(Color.rgb(10, 14, 12))
        configureLight()
        loadModel()
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!started) return
        started = false
        choreographer.removeFrameCallback(frameCallback)
        loaderThread?.interrupt()
        loaderThread = null
    }

    fun destroy() {
        stop()
    }

    private fun configureLight() {
        val engine = viewer.engine
        val lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.95f, 0.82f)
            .intensity(80_000.0f)
            .direction(0.35f, -1.0f, -0.45f)
            .castShadows(true)
            .build(engine, lightEntity)
        viewer.scene.addEntity(lightEntity)
    }

    private fun loadModel() {
        loaderThread = Thread {
            try {
                val bytes = BufferedInputStream(URL(MODEL_URL).openStream()).use { input ->
                    input.readBytes()
                }
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes).flip()
                surfaceView.post {
                    if (!started) return@post
                    viewer.loadModelGltfAsync(buffer) { _ ->
                        ByteBuffer.allocateDirect(0)
                    }
                    viewer.transformToUnitCube()
                    viewer.view.dynamicResolutionOptions =
                        viewer.view.dynamicResolutionOptions.apply {
                            enabled = true
                        }
                    viewer.view.ambientOcclusionOptions =
                        viewer.view.ambientOcclusionOptions.apply {
                            enabled = true
                        }
                    viewer.view.bloomOptions =
                        viewer.view.bloomOptions.apply {
                            enabled = true
                        }
                }
            } catch (_: Exception) {
                // The game remains playable with the procedural fallback scene.
            }
        }.also {
            it.name = "BGame-GLB-Loader"
            it.start()
        }
    }
}
