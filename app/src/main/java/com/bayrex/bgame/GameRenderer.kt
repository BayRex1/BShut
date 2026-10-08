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
    private val renderer = SceneRenderer()
    fun orbitCamera(dx: Float, dy: Float) = renderer.orbitCamera(dx, dy)
    fun zoomCamera(delta: Float) = renderer.zoomCamera(delta)

    init {
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun start() { state = State.LOADING; onMenuVisibilityChanged?.invoke(false); renderer.restart() }

    inner class SceneRenderer : GLSurfaceView.Renderer {
        private val scene = World(appContext)
        private var lastNs = 0L
        fun restart() { gameTime = 0f; state = State.LOADING }
        fun orbitCamera(dx: Float, dy: Float) { scene.orbit(dx, dy) }
        fun zoomCamera(delta: Float) { scene.zoom(delta) }

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
                State.CRASH -> { gameTime += dt; if (gameTime > 9f) { state = State.MENU; gameTime = 0f; onMenuVisibilityChanged?.invoke(true) } }
                State.MENU -> {}
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            scene.draw(state, gameTime)
        }
    }

    class World(private val context: Context) {
        private lateinit var cube: Mesh; private lateinit var rounded: Mesh; private lateinit var cylinder: Mesh; private lateinit var shader: Shader
        private lateinit var treeGlb: StaticGlbModel; private var runningGlb: RunningGlbModel? = null
        private val proj=FloatArray(16); private val view=FloatArray(16); private val model=FloatArray(16); private val vp=FloatArray(16); private val mvp=FloatArray(16)
        private var cameraYaw=0f; private var cameraPitch=14f; private var cameraDistance=6f
        fun init(){ shader=Shader(); cube=Mesh.box(); rounded=Mesh.sphere(20,14); cylinder=Mesh.cylinder(24); treeGlb=StaticGlbModel(context.assets,"models/tree.glb",scale=.01f); runningGlb=try{RunningGlbModel(context.assets,"models/running.glb",scale=.01f)}catch(_:Exception){null} }
        fun resize(w:Int,h:Int){Matrix.perspectiveM(proj,0,58f,w.toFloat()/h,.08f,260f)}
        fun orbit(dx:Float,dy:Float){cameraYaw=(cameraYaw+dx*.32f)%360f;cameraPitch=(cameraPitch+dy*.20f).coerceIn(-8f,58f)}
        fun zoom(d:Float){cameraDistance=(cameraDistance+d).coerceIn(3f,13f)}
        fun draw(s:State,t:Float){if(s==State.MENU||s==State.LOADING){cameraMenu(t);drawBackdrop()}else{cameraRunner(t);drawForest(t);runningGlb?.draw(vp,0f,0f,runnerZ(t),t,180f,1f)}}
        private fun runnerZ(t:Float)=-4f-t*2.8f
        private fun cameraMenu(t:Float){val o=sin(t*.22f)*.16f;Matrix.setLookAtM(view,0,o,1.58f,6.15f,0f,1.15f,0f,0f,1f,0f)}
        private fun cameraRunner(t:Float){val rz=runnerZ(t);val y=Math.toRadians(cameraYaw.toDouble()).toFloat();val p=Math.toRadians(cameraPitch.toDouble()).toFloat();val h=cameraDistance*cos(p);Matrix.setLookAtM(view,0,sin(y)*h,1f+sin(p)*cameraDistance,rz+cos(y)*h,0f,.9f,rz,0f,1f,0f)}
        private fun drawBackdrop(){ground(0f,-.15f,0f,24f,.18f,24f,.08f,.105f,.07f);for(i in -6..6){tree(i*2.55f,-1f,-2.5f,1f+((i and 1)*.18f));tree(i*2.9f,1.3f,-8f,.85f)}}
        private fun drawForest(t:Float){val rz=runnerZ(t);ground(0f,-.10f,rz-48f,24f,.22f,58f,.09f,.12f,.075f);ground(0f,.015f,rz-48f,3.5f,.07f,58f,.16f,.145f,.115f);val sc=(t*2.8f)%8f;for(i in 0..19){val z=rz-8f-i*6.8f+sc;val side=if(i%2==0)-1f else 1f;realTree(side*(3.9f+abs(sin(i.toFloat()))*1.6f),-.55f,z,.82f+abs(sin(i.toFloat()))*.32f);realTree(-side*(4.4f+abs(cos(i.toFloat()))*1.4f),-.25f,z-3f,.72f+abs(cos(i.toFloat()))*.35f)};for(i in 0..22){val x=sin(i*7.13f)*4.6f;val z=rz-3f-i*3.1f+sc;rock(x,.02f,z,.18f+(i%3)*.09f,.12f,.25f+(i%2)*.12f);grassTuft(x+.3f,.04f,z-.25f)}}
        private fun realTree(x:Float,y:Float,z:Float,s:Float)=treeGlb.draw(vp,x,y,z,(x*17f)%360f,-90f,s*2.6f)
        private fun tree(x:Float,y:Float,z:Float,s:Float){trunk(x,y+s,z,.24f*s,2f*s);foliage(x,y+2.65f*s,z,.95f*s);foliage(x-.55f*s,y+2.3f*s,z+.18f,.65f*s);foliage(x+.58f*s,y+2.4f*s,z-.12f,.68f*s)}
        private fun foliage(x:Float,y:Float,z:Float,s:Float)=sphere(x,y,z,s*1.05f,s*.82f,s*.92f,.075f,.18f,.07f)
        private fun trunk(x:Float,y:Float,z:Float,r:Float,h:Float)=cyl(x,y,z,r,h,r,0f,0f,0f,.19f,.105f,.055f)
        private fun ground(x:Float,y:Float,z:Float,a:Float,b:Float,c:Float,r:Float,g:Float,bl:Float)=box(x,y,z,a,b,c,r,g,bl)
        private fun box(x:Float,y:Float,z:Float,a:Float,b:Float,c:Float,r:Float,g:Float,bl:Float)=drawMesh(cube,x,y,z,a,b,c,r,g,bl)
        private fun sphere(x:Float,y:Float,z:Float,a:Float,b:Float,c:Float,r:Float,g:Float,bl:Float)=drawMesh(rounded,x,y,z,a,b,c,r,g,bl)
        private fun cyl(x:Float,y:Float,z:Float,a:Float,b:Float,c:Float,rx:Float,ry:Float,rz:Float,r:Float,g:Float,bl:Float){Matrix.setIdentityM(model,0);Matrix.translateM(model,0,x,y,z);Matrix.rotateM(model,0,rx,1f,0f,0f);Matrix.rotateM(model,0,ry,0f,1f,0f);Matrix.rotateM(model,0,rz,0f,0f,1f);Matrix.scaleM(model,0,a,b,c);submit(cylinder,model,r,g,bl)}
        private fun drawMesh(m:Mesh,x:Float,y:Float,z:Float,a:Float,b:Float,c:Float,r:Float,g:Float,bl:Float){Matrix.setIdentityM(model,0);Matrix.translateM(model,0,x,y,z);Matrix.scaleM(model,0,a,b,c);submit(m,model,r,g,bl)}
        private fun submit(m:Mesh,mdl:FloatArray,r:Float,g:Float,b:Float){Matrix.multiplyMM(vp,0,proj,0,view,0);Matrix.multiplyMM(mvp,0,vp,0,mdl,0);shader.draw(m,mvp,mdl,r,g,b)}
        private fun grassTuft(x:Float,y:Float,z:Float){for(i in 0..2)box(x+(i-1)*.035f,y+.09f,z+(i-1)*.025f,.025f,.18f,.025f,.10f,.16f,.07f)}
        private fun rock(x:Float,y:Float,z:Float,a:Float,b:Float,c:Float)=sphere(x,y+b,z,a,b,c,.20f,.20f,.17f)
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
