package com.bayrex.bgame

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import kotlin.math.*

class GameRenderer(context: Context) {
    private val appContext = context
    enum class State { MENU, LOADING, DRIVE, CRASH }
    @Volatile var state = State.MENU
    @Volatile var gameTime = 0f
    var onMenuVisibilityChanged: ((Boolean) -> Unit)? = null
    val glView = GLSurfaceView(appContext)
    private val renderer = SceneRenderer()\n\n    fun orbitCamera(dx: Float, dy: Float) = renderer.orbitCamera(dx, dy)\n    fun zoomCamera(delta: Float) = renderer.zoomCamera(delta)

    init {
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun start() { state = State.LOADING; onMenuVisibilityChanged?.invoke(false); renderer.restart() }

    inner class SceneRenderer : GLSurfaceView.Renderer {
        private val scene = World(appContext)
        private var lastNs = 0L
        fun restart() { gameTime = 0f; state = State.LOADING }\n        fun orbitCamera(dx: Float, dy: Float) { scene.orbit(dx, dy) }\n        fun zoomCamera(delta: Float) { scene.zoom(delta) }

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, cfg: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(.015f, .022f, .017f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            lastNs = System.nanoTime()
            scene.init()
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            scene.resize(w, h)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1_000_000_000.0).toFloat().coerceIn(.008f, .05f)
            lastNs = now
            when (state) {
                State.LOADING -> { gameTime += dt; if (gameTime > 2.5f) { state = State.DRIVE; gameTime = 0f } }
                State.DRIVE -> { gameTime += dt }
                State.CRASH -> { gameTime += dt }
                State.MENU -> {}
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            scene.draw(state, gameTime)
        }
    }

    class World(private val context: Context) {
        private lateinit var cube: Mesh
        private lateinit var rounded: Mesh
        private lateinit var capsule: Mesh
        private lateinit var shader: Shader
        private lateinit var treeGlb: StaticGlbModel
        private var runningGlb: RunningGlbModel? = null
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val vp = FloatArray(16)
        private val mvp = FloatArray(16)
        private var cameraYaw = 0f
        private var cameraPitch = 14f
        private var cameraDistance = 6.0f

        fun init() {
            shader = Shader()
            cube = Mesh.box()
            rounded = Mesh.sphere(20, 14)
            capsule = Mesh.capsule(16, 8)
            treeGlb = StaticGlbModel(context.assets, "models/tree.glb", scale = 0.01f)
            runningGlb = try { RunningGlbModel(context.assets, "models/running.glb", scale = 0.01f) } catch (_: Exception) { null }
        }

        fun resize(w: Int, h: Int) {
            Matrix.perspectiveM(proj, 0, 58f, w.toFloat() / h, .08f, 260f)
        }

        fun orbit(dx: Float, dy: Float) {
            cameraYaw = (cameraYaw + dx * 0.32f) % 360f
            cameraPitch = (cameraPitch + dy * 0.20f).coerceIn(-8f, 58f)
        }

        fun zoom(delta: Float) {
            cameraDistance = (cameraDistance + delta).coerceIn(3f, 13f)
        }

        fun draw(s: State, t: Float) {
            if (s == State.MENU || s == State.LOADING) {
                cameraMenu(t)
                drawBackdrop()
            } else {
                cameraRunner(t)
                drawForest(t)
                runningGlb?.draw(vp, 0f, 0f, runnerZ(t), t, yaw = 180f, instanceScale = 1f)
            }
        }

        private fun runnerZ(t: Float): Float = -4f - t * 2.8f

        private fun cameraMenu(t: Float) {
            val orbit = sin(t * .22f) * .16f
            Matrix.setLookAtM(view, 0, orbit, 1.58f, 6.15f, 0f, 1.15f, 0f, 0f, 1f, 0f)
        }

        private fun cameraRunner(t: Float) {
            val rz = runnerZ(t)
            val yaw = Math.toRadians(cameraYaw.toDouble()).toFloat()
            val pitch = Math.toRadians(cameraPitch.toDouble()).toFloat()
            val horizontal = cameraDistance * cos(pitch)
            val eyeX = sin(yaw) * horizontal
            val eyeY = 1.0f + sin(pitch) * cameraDistance
            val eyeZ = rz + cos(yaw) * horizontal
            Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, 0f, 0.9f, rz, 0f, 1f, 0f)
        }

        private fun drawBackdrop() {
            ground(0f, -.15f, 0f, 24f, .18f, 24f, .08f, .105f, .07f)
            for (i in -6..6) {
                tree(i * 2.55f, -1.0f, -2.5f, 1f + ((i and 1) * .18f))
                tree(i * 2.9f, 1.3f, -8f, .85f)
            }
        }

        private fun drawForest(t: Float) {
            val rz = runnerZ(t)
            ground(0f, -.10f, rz - 48f, 24f, .22f, 58f, .09f, .12f, .075f)
            ground(0f, .015f, rz - 48f, 3.5f, .07f, 58f, .16f, .145f, .115f)
            val scroll = (t * 2.8f) % 8f
            for (i in 0..19) {
                val localZ = -8f - i * 6.8f + scroll
                val side = if (i % 2 == 0) -1f else 1f
                val x1 = side * (3.9f + abs(sin(i.toFloat())) * 1.6f)
                val x2 = -side * (4.4f + abs(cos(i.toFloat())) * 1.4f)
                realTree(x1, -.55f, rz + localZ, .82f + abs(sin(i.toFloat())) * .32f)
                realTree(x2, -.25f, rz + localZ - 3.0f, .72f + abs(cos(i.toFloat())) * .35f)
            }
            for (i in 0..22) {
                val x = sin(i * 7.13f) * 4.6f
                val z = rz - 3f - i * 3.1f + scroll
                rock(x, .02f, z, .18f + (i % 3) * .09f, .12f, .25f + (i % 2) * .12f)
                grassTuft(x + .3f, .04f, z - .25f)
            }
        }

        private fun realTree(x: Float, y: Float, z: Float, scale: Float) {
            treeGlb.draw(vp, x, y, z, yaw = (x * 17f) % 360f, pitch = -90f, instanceScale = scale * 2.6f)
        }

        private fun tree(x: Float, y: Float, z: Float, scale: Float) {
            trunk(x, y + 1.0f * scale, z, .24f * scale, 2.0f * scale)
            foliage(x, y + 2.65f * scale, z, .95f * scale)
            foliage(x - .55f * scale, y + 2.3f * scale, z + .18f, .65f * scale)
            foliage(x + .58f * scale, y + 2.4f * scale, z - .12f, .68f * scale)
        }

        private fun foliage(x: Float, y: Float, z: Float, ss: Float) =
            sphere(x, y, z, ss * 1.05f, ss * .82f, ss * .92f, .075f, .18f, .07f)

        private fun trunk(x: Float, y: Float, z: Float, r: Float, h: Float) =
            cyl(x, y, z, r, h, r, 0f, 0f, 0f, .19f, .105f, .055f)

        private fun ground(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) =
            box(x, y, z, sx, sy, sz, r, g, b)

        private fun box(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) =
            drawMesh(cube, x, y, z, sx, sy, sz, r, g, b)

        private fun sphere(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) =
            drawMesh(rounded, x, y, z, sx, sy, sz, r, g, b)

        private fun cyl(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, rx: Float, ry: Float, rz: Float, r: Float, g: Float, b: Float) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, y, z)
            Matrix.rotateM(model, 0, rx, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, ry, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, rz, 0f, 0f, 1f)
            Matrix.scaleM(model, 0, sx, sy, sz)
            submit(cylinder, model, r, g, b)
        }

        private fun drawMesh(m: Mesh, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, y, z)
            Matrix.scaleM(model, 0, sx, sy, sz)
            submit(m, model, r, g, b)
        }

        private fun submit(m: Mesh, mdl: FloatArray, r: Float, g: Float, b: Float) {
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.multiplyMM(mvp, 0, vp, 0, mdl, 0)
            shader.draw(m, mvp, mdl, r, g, b)
        }

        private fun grassTuft(x: Float, y: Float, z: Float) {
            for (i in 0..2) box(x + (i - 1) * .035f, y + .09f, z + (i - 1) * .025f, .025f, .18f, .025f, .10f, .16f, .07f)
        }

        private fun rock(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float) =
            sphere(x, y + sy, z, sx, sy, sz, .20f, .20f, .17f)
    }

