package com.bayrex.bgame

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import kotlin.math.*

class GameRenderer(context: Context) {
    enum class State { MENU, LOADING, DRIVE, CRASH }
    @Volatile var state = State.MENU
    @Volatile var gameTime = 0f
    var onMenuVisibilityChanged: ((Boolean) -> Unit)? = null
    val glView = GLSurfaceView(context)
    private val renderer = SceneRenderer()

    init {
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun start() { state = State.LOADING; onMenuVisibilityChanged?.invoke(false); renderer.restart() }

    inner class SceneRenderer : GLSurfaceView.Renderer {
        private val scene = World(context)
        private var lastNs = 0L
        fun restart() { gameTime = 0f; state = State.LOADING }

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
                State.DRIVE -> { gameTime += dt; if (gameTime > 34f) { state = State.CRASH; gameTime = 0f } }
                State.CRASH -> { gameTime += dt; if (gameTime > 9f) { state = State.MENU; gameTime = 0f; onMenuVisibilityChanged?.invoke(true) } }
                State.MENU -> {}
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            scene.draw(state, gameTime)
        }
    }

    class World(private val context: Context) {
        private lateinit var cube: Mesh
        private lateinit var rounded: Mesh
        private lateinit var cylinder: Mesh
        private lateinit var capsule: Mesh
        private lateinit var shader: Shader
        private lateinit var treeGlb: StaticGlbModel
        private lateinit var terroristGlb: StaticGlbModel
        private var jeepGlb: StaticGlbModel? = null
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val vp = FloatArray(16)
        private val mvp = FloatArray(16)
        private var width = 1
        private var height = 1

        fun init() {
            shader = Shader()
            cube = Mesh.box()
            rounded = Mesh.sphere(20, 14)
            cylinder = Mesh.cylinder(24)
            capsule = Mesh.capsule(16, 8)
            // Real RigModels assets: textured tree + Zone 9 enemy.
            // The tree source has a baked 0.01 root scale and a -90° X orientation.
            treeGlb = StaticGlbModel(context.assets, "models/tree.glb", scale = 0.01f)
            terroristGlb = StaticGlbModel(context.assets, "models/zone9_terrorist.glb", scale = 1f, onlyMeshes = setOf(0))
            // Optional real military jeep: drop jeep.glb beside the other assets.
            // Until then the detailed procedural jeep remains the safe fallback.
            jeepGlb = try { StaticGlbModel(context.assets, "models/jeep.glb", scale = 1f) } catch (_: Exception) { null }
        }

        fun resize(w: Int, h: Int) {
            width = w; height = h
            Matrix.perspectiveM(proj, 0, 58f, w.toFloat() / h, .08f, 260f)
        }

        fun draw(s: State, t: Float) {
            if (s == State.MENU || s == State.LOADING) {
                cameraMenu(t)
                drawBackdrop()
                drawSoldier(t, menu = true)
            } else {
                cameraDrive(t)
                drawForest(t)
                drawJeep(t)
                drawDriver(t)
                drawPassenger(t)
            }
            if (s == State.CRASH) {
                // The HUD supplies the black cinematic cut; keep the 3D scene frozen underneath.
            }
        }

        private fun cameraMenu(t: Float) {
            val orbit = sin(t * .22f) * .16f
            Matrix.setLookAtM(view, 0, orbit, 1.58f, 6.15f, 0f, 1.25f, 0f, 0f, 1f, 0f)
        }

        private fun cameraDrive(t: Float) {
            val k = smoothstep((t - 10f) / 7f)
            val jeepZ = -5.4f - t * .31f
            // The interior camera follows the vehicle; the old fixed Z made it
            // drift into the forest as the jeep drove forward.
            val outside = floatArrayOf(4.5f, 2.75f, jeepZ + 11.8f)
            val inside = floatArrayOf(-.48f, 1.42f, jeepZ + .28f)
            val eyeX = lerp(outside[0], inside[0], k)
            val eyeY = lerp(outside[1], inside[1], k)
            val eyeZ = lerp(outside[2], inside[2], k)
            val lookX = lerp(0f, -.48f, k)
            val lookY = lerp(.95f, 1.36f, k)
            val lookZ = lerp(jeepZ - 6.2f, jeepZ - 7.0f, k)
            val shake = if (t > 28f) sin(t * 17f) * .018f else sin(t * 4.5f) * .006f
            Matrix.setLookAtM(view, 0, eyeX + shake, eyeY, eyeZ, lookX, lookY, lookZ, 0f, 1f, 0f)
        }

        private fun drawBackdrop() {
            ground(0f, -.15f, 0f, 24f, .18f, 24f, .08f, .105f, .07f)
            for (i in -6..6) {
                tree(i * 2.55f, -1.0f, -2.5f, 1f + ((i and 1) * .18f))
                tree(i * 2.9f, 1.3f, -8f, .85f)
            }
            rock(-2.4f, .05f, -2.4f, .45f, .24f, .7f)
            rock(2.7f, .02f, -3.6f, .35f, .2f, .55f)
        }

        private fun drawForest(t: Float) {
            ground(0f, -.1f, -30f, 24f, .22f, 58f, .09f, .12f, .075f)
            // Broken muddy road
            ground(0f, .015f, -29f, 3.5f, .07f, 60f, .16f, .145f, .115f)
            for (i in -6..6) {
                val z = -3.5f - i * 6.8f
                realTree(i * 2.65f + sin(i.toFloat()) * .5f, -.55f, z, .82f + abs(sin(i.toFloat())) * .32f)
                realTree(i * 2.85f + .8f, -.25f, z - 3.0f, .72f + abs(cos(i.toFloat())) * .35f)
            }
            // A real textured Zone 9 model is placed ahead of the jeep as a world prop.
            if (t > 9f) {
                terroristGlb.draw(vp, 2.9f, 0f, -25f, yaw = 180f, instanceScale = 1.05f)
            }
            for (i in 0..18) {
                val x = sin(i * 7.13f) * 4.6f
                val z = -2f - i * 3.3f
                rock(x, .02f, z, .18f + (i % 3) * .09f, .12f, .25f + (i % 2) * .12f)
                grassTuft(x + .3f, .04f, z - .25f)
            }
        }

        private fun realTree(x: Float, y: Float, z: Float, scale: Float) {
            // The original tree is about 1.9m tall after its baked 0.01 root scale.
            // 2.6x gives a believable forest tree without filling the camera.
            treeGlb.draw(vp, x, y, z, yaw = (x * 17f) % 360f, pitch = -90f, instanceScale = scale * 2.6f)
        }

        private fun tree(x: Float, y: Float, z: Float, scale: Float) {
            trunk(x, y + 1.0f * scale, z, .24f * scale, 2.0f * scale)
            branch(x - .34f * scale, y + 1.8f * scale, z + .04f, .10f * scale, .9f * scale, -32f)
            branch(x + .38f * scale, y + 2.05f * scale, z - .08f, .095f * scale, .8f * scale, 38f)
            foliage(x, y + 2.65f * scale, z, .95f * scale)
            foliage(x - .55f * scale, y + 2.3f * scale, z + .18f, .65f * scale)
            foliage(x + .58f * scale, y + 2.4f * scale, z - .12f, .68f * scale)
        }

        private fun foliage(x: Float, y: Float, z: Float, s: Float) {
            sphere(x, y, z, s * 1.05f, s * .82f, s * .92f, .075f, .18f, .07f)
            sphere(x + .22f * s, y + .24f * s, z - .08f * s, s * .62f, s * .55f, s * .58f, .095f, .23f, .085f)
        }

        private fun trunk(x: Float, y: Float, z: Float, r: Float, h: Float) =
            cyl(x, y, z, r, h, r, 0f, 0f, 0f, .19f, .105f, .055f)

        private fun branch(x: Float, y: Float, z: Float, r: Float, h: Float, rz: Float) =
            cyl(x, y, z, r, h, r, 0f, 0f, rz, .17f, .09f, .045f)

        private fun drawSoldier(t: Float, menu: Boolean) {
            val bob = sin(t * 1.7f) * .018f
            val breath = sin(t * 1.15f) * .012f
            val y = bob
            shadow(0f, .03f, 0f, .72f, .34f)
            // boots and articulated legs
            boot(-.24f, .32f + y, .02f, -.08f)
            boot(.24f, .32f + y, .02f, .08f)
            leg(-.24f, .72f + y, 0f, -.08f)
            leg(.24f, .72f + y, 0f, .08f)
            // plate carrier, belt and pouches
            box(0f, 1.22f + y, 0f, .38f, .54f, .22f, .105f, .14f, .145f)
            box(0f, 1.08f + y, -.02f, .43f, .13f, .25f, .075f, .09f, .095f)
            for (x in listOf(-.28f, -.09f, .09f, .28f)) box(x, 1.08f + y, -.27f, .07f, .14f, .09f, .11f, .13f, .13f)
            // neck/head/helmet
            cyl(0f, 1.68f + y, 0f, .105f, .16f, .105f, 0f, 0f, 0f, .55f, .38f, .29f)
            sphere(0f, 1.9f + y, 0f, .25f, .30f, .25f, .54f, .38f, .29f)
            sphere(0f, 2.12f + y, 0f, .29f, .17f, .30f, .075f, .095f, .10f)
            box(0f, 2.04f + y, -.25f, .25f, .055f, .08f, .055f, .065f, .07f)
            // arms with elbow bend
            arm(-.48f, 1.30f + y, -.02f, -1f, t)
            arm(.48f, 1.30f + y, -.02f, 1f, t)
            // rifle, stock, receiver, handguard, barrel, sight
            box(0f, 1.26f + y, -.39f, .105f, .12f, .52f, .07f, .075f, .07f)
            box(0f, 1.27f + y, -.88f, .075f, .075f, .5f, .045f, .05f, .047f)
            cyl(0f, 1.27f + y, -1.18f, .035f, .32f, .035f, 90f, 0f, 0f, .035f, .038f, .04f)
            box(0f, 1.38f + y, -.67f, .06f, .07f, .16f, .035f, .04f, .04f)
            if (!menu) box(0f, 1.31f + y, -.02f, .04f, .16f, .07f, .12f, .14f, .14f)
            // subtle idle turn
            if (menu) { /* centered hero pose */ }
        }

        private fun boot(x: Float, y: Float, z: Float, toe: Float) =
            box(x + toe, y, z - .04f, .17f, .17f, .28f, .045f, .05f, .052f)

        private fun leg(x: Float, y: Float, z: Float, s: Float) =
            capsulePart(x + s * .15f, y, z, .16f, .46f, .12f, .11f, .13f, .14f)

        private fun arm(x: Float, y: Float, z: Float, side: Float, t: Float) {
            val lift = .04f + sin(t * 1.2f + side) * .025f
            capsulePart(x, y - .12f, z - .02f, .13f, .38f, .10f, .105f, .13f, .14f)
            capsulePart(x - side * .06f, y - .36f + lift, z - .18f, .105f, .30f, .09f, .09f, .11f, .12f)
            sphere(x - side * .06f, y - .52f + lift, z - .31f, .10f, .11f, .10f, .53f, .38f, .29f)
        }

        private fun drawJeep(t: Float) {
            val z = -5.4f - t * .31f
            jeepGlb?.let {
                // Military Jeep source uses a 1.3 visual scale and faces +Z,
                // so rotate it 180° to match BGame's -Z driving direction.
                it.draw(vp, 0f, .99f, z, yaw = 180f, instanceScale = 1.3f)
                return
            }
            shadow(0f, .05f, z, 1.35f, .48f)
            // chassis / fenders / hood
            box(0f, .64f, z, 2.05f, .40f, 3.05f, .055f, .07f, .065f)
            box(0f, .93f, z + .73f, 1.82f, .25f, .92f, .065f, .085f, .07f)
            box(0f, 1.10f, z - .48f, 1.78f, .78f, 1.42f, .05f, .065f, .055f)
            // windshield pillars and glass
            for (x in listOf(-.83f, .83f)) {
                box(x, 1.48f, z - .28f, .075f, .72f, .08f, .025f, .03f, .032f)
            }
            // Do not draw an opaque windshield: the first-person camera looks through
            // this area. The pillars remain, giving a clear cabin frame.
            // grille / bumper / lights
            box(0f, .74f, z - 2.0f, 1.82f, .20f, .12f, .035f, .04f, .038f)
            box(0f, .79f, z - 2.08f, 1.45f, .15f, .10f, .08f, .085f, .075f)
            for (x in listOf(-.62f, .62f)) sphere(x, .92f, z - 2.02f, .16f, .11f, .08f, .70f, .68f, .45f)
            // seats, dash and steering wheel
            box(-.56f, 1.00f, z + .15f, .48f, .62f, .48f, .045f, .055f, .055f)
            box(.56f, 1.00f, z + .15f, .48f, .62f, .48f, .045f, .055f, .055f)
            box(-.50f, 1.31f, z - .38f, .90f, .22f, .10f, .035f, .045f, .045f)
            cyl(-.60f, 1.30f, z - .62f, .19f, .035f, .19f, 90f, 0f, 0f, .02f, .022f, .023f)
            // roll cage
            for (x in listOf(-.82f, .82f)) {
                cyl(x, 1.72f, z + .48f, .055f, 1.35f, .055f, 0f, 0f, 0f, .035f, .04f, .04f)
                cyl(x, 1.72f, z - .85f, .055f, 1.35f, .055f, 0f, 0f, 0f, .035f, .04f, .04f)
            }
            // wheels with hubs and tread blocks
            for (x in listOf(-1.10f, 1.10f)) for (zz in listOf(z - 1.02f, z + 1.02f)) {
                cyl(x, .50f, zz, .43f, .20f, .43f, 90f, 0f, 0f, .025f, .028f, .03f)
                cyl(x + if (x < 0) .13f else -.13f, .50f, zz, .18f, .22f, .18f, 90f, 0f, 0f, .12f, .13f, .13f)
            }
        }

        private fun drawDriver(t: Float) {
            val z = -4.82f - t * .31f
            if (t < 10f) return
            val fade = smoothstep((t - 10f) / 2f)
            characterSeat(-.55f, z, fade, .10f)
        }

        private fun drawPassenger(t: Float) {
            val z = -4.82f - t * .31f
            if (t < 10f) return
            val fade = smoothstep((t - 10f) / 2f)
            // civilian: modest casual top + skirt, natural seated silhouette
            sphere(.55f, 1.48f, z, .215f * fade, .265f * fade, .215f * fade, .64f, .48f, .36f)
            sphere(.55f, 1.72f, z, .25f * fade, .14f * fade, .25f * fade, .16f, .075f, .045f)
            box(.55f, 1.05f, z, .43f * fade, .64f * fade, .32f * fade, .20f, .24f, .28f)
            box(.55f, .68f, z, .50f * fade, .18f * fade, .39f * fade, .16f, .17f, .18f)
            for (x in listOf(.40f, .70f)) {
                capsulePart(x, .38f, z + .01f, .12f * fade, .38f * fade, .10f, .57f, .46f, .38f)
                boot(x, .13f, z - .02f, 0f)
            }
            capsulePart(.28f, 1.10f, z - .03f, .10f, .38f, .09f, .64f, .48f, .36f)
            capsulePart(.82f, 1.10f, z - .03f, .10f, .38f, .09f, .64f, .48f, .36f)
        }

        private fun characterSeat(x: Float, z: Float, fade: Float, suitShift: Float) {
            sphere(x, 1.48f, z, .23f * fade, .27f * fade, .23f * fade, .54f, .38f, .29f)
            sphere(x, 1.70f, z, .27f * fade, .16f * fade, .27f * fade, .07f, .09f, .10f)
            box(x, 1.04f, z, .45f * fade, .65f * fade, .34f * fade, .10f, .14f, .145f)
            capsulePart(x - .18f, .78f, z, .13f, .34f, .10f, .105f, .13f, .14f)
            capsulePart(x + .18f, .78f, z, .13f, .34f, .10f, .105f, .13f, .14f)
            box(x, .52f, z, .50f * fade, .16f * fade, .42f * fade, .07f, .09f, .095f)
        }

        private fun grassTuft(x: Float, y: Float, z: Float) {
            for (i in 0..2) {
                box(x + (i - 1) * .035f, y + .09f, z + (i - 1) * .025f, .025f, .18f, .025f, .10f, .16f, .07f)
            }
        }

        private fun rock(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float) =
            sphere(x, y + sy, z, sx, sy, sz, .20f, .20f, .17f)

        private fun shadow(x: Float, y: Float, z: Float, sx: Float, sz: Float) =
            sphere(x, y, z, sx, .012f, sz, .018f, .022f, .018f)

        private fun ground(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) =
            box(x, y, z, sx, sy, sz, r, g, b)

        private fun box(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) =
            drawMesh(cube, x, y, z, sx, sy, sz, r, g, b)

        private fun sphere(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) =
            drawMesh(rounded, x, y, z, sx, sy, sz, r, g, b)

        private fun capsulePart(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) =
            drawMesh(capsule, x, y, z, sx, sy, sz, r, g, b)

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

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smoothstep(x: Float): Float {
            val v = x.coerceIn(0f, 1f)
            return v * v * (3f - 2f * v)
        }
    }

    class Mesh(private val v: FloatArray, private val n: FloatArray, private val idx: ShortArray) {
        private val vb = java.nio.ByteBuffer.allocateDirect(v.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v).position(0) }
        private val nb = java.nio.ByteBuffer.allocateDirect(n.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(n).position(0) }
        private val ib = java.nio.ByteBuffer.allocateDirect(idx.size * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx).position(0) }

        fun draw(pos: Int, normal: Int) {
            vb.position(0); GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, vb); GLES20.glEnableVertexAttribArray(pos)
            nb.position(0); GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 0, nb); GLES20.glEnableVertexAttribArray(normal)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, idx.size, GLES20.GL_UNSIGNED_SHORT, ib)
            GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(normal)
        }

        companion object {
            fun box(): Mesh {
                val p = floatArrayOf(
                    -1f,-1f,-1f, 1f,-1f,-1f, 1f,1f,-1f,-1f,1f,-1f,
                    -1f,-1f,1f, 1f,-1f,1f, 1f,1f,1f,-1f,1f,1f)
                val idx = shortArrayOf(0,1,2,2,3,0, 1,5,6,6,2,1, 5,4,7,7,6,5, 4,0,3,3,7,4, 3,2,6,6,7,3, 4,5,1,1,0,4)
                val nn = floatArrayOf(
                    0f,0f,-1f,0f,0f,-1f,0f,0f,-1f,0f,0f,-1f,
                    0f,0f,1f,0f,0f,1f,0f,0f,1f,0f,0f,1f)
                return Mesh(p, nn, idx)
            }

            fun sphere(lat: Int, lon: Int): Mesh {
                val vs = ArrayList<Float>(); val ns = ArrayList<Float>(); val ix = ArrayList<Short>()
                for (i in 0..lat) {
                    val a = PI * i / lat; val y = cos(a).toFloat(); val rr = sin(a).toFloat()
                    for (j in 0..lon) {
                        val b = 2 * PI * j / lon
                        val x = (rr * cos(b)).toFloat(); val z = (rr * sin(b)).toFloat()
                        vs.add(x); vs.add(y); vs.add(z); ns.add(x); ns.add(y); ns.add(z)
                    }
                }
                for (i in 0 until lat) for (j in 0 until lon) {
                    val a = (i * (lon + 1) + j).toShort(); val b = (a + lon + 1).toShort()
                    ix.add(a); ix.add(b); ix.add((a + 1).toShort()); ix.add((a + 1).toShort()); ix.add(b); ix.add((b + 1).toShort())
                }
                return Mesh(vs.toFloatArray(), ns.toFloatArray(), ix.toShortArray())
            }

            fun capsule(seg: Int, rings: Int): Mesh {
                val vs = ArrayList<Float>(); val ns = ArrayList<Float>(); val ix = ArrayList<Short>()
                val total = rings * 2 + 2
                for (i in 0..total) {
                    val u = i.toFloat() / total
                    val a = PI * (u - .5)
                    val y = (sin(a)).toFloat()
                    val rr = (cos(a)).toFloat()
                    val centerY = if (u < .5f) -.5f else .5f
                    for (j in 0..seg) {
                        val b = 2 * PI * j / seg
                        val x = rr * cos(b); val z = rr * sin(b); val yy = y + centerY
                        vs.add(x.toFloat()); vs.add(yy.toFloat()); vs.add(z.toFloat())
                        ns.add(x.toFloat()); ns.add(y.toFloat()); ns.add(z.toFloat())
                    }
                }
                for (i in 0 until total) for (j in 0 until seg) {
                    val a = (i * (seg + 1) + j).toShort(); val b = (a + seg + 1).toShort()
                    ix.add(a); ix.add(b); ix.add((a + 1).toShort()); ix.add((a + 1).toShort()); ix.add(b); ix.add((b + 1).toShort())
                }
                return Mesh(vs.toFloatArray(), ns.toFloatArray(), ix.toShortArray())
            }

            fun cylinder(seg: Int): Mesh {
                val vs = ArrayList<Float>(); val ns = ArrayList<Float>(); val ix = ArrayList<Short>()
                for (i in 0 until seg) {
                    val a = 2 * PI * i / seg; val x = cos(a).toFloat(); val z = sin(a).toFloat()
                    vs.add(x); vs.add(-1f); vs.add(z); ns.add(x); ns.add(0f); ns.add(z)
                    vs.add(x); vs.add(1f); vs.add(z); ns.add(x); ns.add(0f); ns.add(z)
                }
                for (i in 0 until seg) {
                    val n = ((i + 1) % seg) * 2; val p = i * 2
                    ix.add(p.toShort()); ix.add(n.toShort()); ix.add((p + 1).toShort())
                    ix.add((p + 1).toShort()); ix.add(n.toShort()); ix.add((n + 1).toShort())
                }
                return Mesh(vs.toFloatArray(), ns.toFloatArray(), ix.toShortArray())
            }
        }
    }

    class Shader {
        private val program: Int
        private val pos: Int
        private val normal: Int
        private val mvpLoc: Int
        private val modelLoc: Int
        private val colorLoc: Int
        private val lightLoc: Int
        private val eyeLoc: Int

        init {
            val vs = """
                attribute vec4 aPosition;
                attribute vec3 aNormal;
                uniform mat4 uMvp;
                uniform mat4 uModel;
                uniform vec3 uLight;
                uniform vec3 uEye;
                varying float vLight;
                varying float vFog;
                void main() {
                    vec3 n = normalize(mat3(uModel) * aNormal);
                    vec3 l = normalize(uLight);
                    float ndl = max(dot(n,l),0.0);
                    vLight = 0.30 + ndl * 0.70;
                    vec4 world = uModel * aPosition;
                    vFog = clamp((length(world.xyz-uEye)-18.0)/75.0,0.0,1.0);
                    gl_Position = uMvp * aPosition;
                }
            """
            val fs = """
                precision mediump float;
                uniform vec3 uColor;
                varying float vLight;
                varying float vFog;
                void main() {
                    vec3 lit = uColor * vLight;
                    vec3 fog = vec3(0.035,0.050,0.040);
                    gl_FragColor = vec4(mix(lit,fog,vFog),1.0);
                }
            """
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
            }
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f); GLES20.glLinkProgram(program)
            pos = GLES20.glGetAttribLocation(program, "aPosition")
            normal = GLES20.glGetAttribLocation(program, "aNormal")
            mvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
            modelLoc = GLES20.glGetUniformLocation(program, "uModel")
            colorLoc = GLES20.glGetUniformLocation(program, "uColor")
            lightLoc = GLES20.glGetUniformLocation(program, "uLight")
            eyeLoc = GLES20.glGetUniformLocation(program, "uEye")
        }

        fun draw(m: Mesh, mvp: FloatArray, model: FloatArray, r: Float, g: Float, b: Float) {
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(modelLoc, 1, false, model, 0)
            GLES20.glUniform3f(colorLoc, r, g, b)
            GLES20.glUniform3f(lightLoc, -.35f, .85f, .42f)
            GLES20.glUniform3f(eyeLoc, 0f, 1.5f, 6f)
            m.draw(pos, normal)
        }
    }
}
