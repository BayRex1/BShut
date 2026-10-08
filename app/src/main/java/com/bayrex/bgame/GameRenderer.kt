package com.bayrex.bgame

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import kotlin.math.*

class GameRenderer(context: Context) {
    enum class State { MENU, LOADING, DRIVE, CRASH }
    @Volatile var state=State.MENU
    @Volatile var gameTime=0f
    val glView=GLSurfaceView(context)
    private val renderer=SceneRenderer()
    init {
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode=GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    fun start(){ state=State.LOADING; renderer.restart() }
    inner class SceneRenderer: GLSurfaceView.Renderer {
        private val scene=World()
        fun restart(){ gameTime=0f; state=State.LOADING }
        override fun onSurfaceCreated(gl:javax.microedition.khronos.opengles.GL10?,cfg:javax.microedition.khronos.egl.EGLConfig?){
            GLES20.glClearColor(.025f,.035f,.025f,1f); GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glEnable(GLES20.GL_CULL_FACE)
            scene.init()
        }
        override fun onSurfaceChanged(gl:javax.microedition.khronos.opengles.GL10?,w:Int,h:Int){ GLES20.glViewport(0,0,w,h); scene.resize(w,h) }
        override fun onDrawFrame(gl:javax.microedition.khronos.opengles.GL10?){
            if(state==State.LOADING){ gameTime+=.016f; if(gameTime>2.5f){state=State.DRIVE;gameTime=0f} }
            else if(state==State.DRIVE){ gameTime+=.016f; if(gameTime>34f){state=State.CRASH;gameTime=0f} }
            else if(state==State.CRASH){ gameTime+=.016f; if(gameTime>9f){state=State.MENU;gameTime=0f} }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            scene.draw(state,gameTime)
        }
    }

    class World {
        private lateinit var cube: Mesh
        private lateinit var sphere: Mesh
        private lateinit var cyl: Mesh
        private lateinit var shader: Shader
        private val proj=FloatArray(16); private val view=FloatArray(16); private val model=FloatArray(16); private val vp=FloatArray(16)
        private var width=1; private var height=1
        fun init(){ shader=Shader(); cube=Mesh.cube(); sphere=Mesh.sphere(14,10); cyl=Mesh.cylinder(12) }
        fun resize(w:Int,h:Int){width=w;height=h;Matrix.perspectiveM(proj,0,62f,w.toFloat()/h,0.1f,300f)}
        fun draw(s:State,t:Float){
            val menu=s==State.MENU || s==State.LOADING
            if(menu){ cameraMenu(t); drawForestBackdrop(); drawSoldier(t); if(s==State.LOADING) return }
            else { cameraDrive(t); drawForest(t); drawJeep(t); drawDriver(t); drawPassenger(t) }
        }
        private fun cameraMenu(t:Float){
            Matrix.setLookAtM(view,0,0f,1.65f,6.8f,0f,1.35f,0f,0f,1f,0f)
            val sway=sin(t*.8f)*.02f; view[12]+=sway
        }
        private fun cameraDrive(t:Float){
            if(t<15f) Matrix.setLookAtM(view,0,4.2f,3.1f,7.0f,0f,1.0f,-6f,0f,1f,0f)
            else Matrix.setLookAtM(view,0,0.25f,1.65f,2.0f,0f,1.5f,-4f,0f,1f,0f)
        }
        private fun drawForestBackdrop(){
            ground(0f,-.08f,0f,25f,.12f,25f,0.11f,.17f,.10f)
            for(i in -5..5){ tree(i*2.4f, -1.2f, -2f); tree(i*2.7f, 1.2f,-7f) }
        }
        private fun drawForest(t:Float){
            ground(0f,-.08f,-5f,18f,.12f,70f,.11f,.17f,.10f)
            // road
            ground(0f,.01f,-18f,5.2f,.06f,70f,.18f,.16f,.14f)
            for(i in -5..5){ tree(i*2.5f, -1.8f, -5f-i*5f); tree(i*2.8f, 2.0f,-9f-i*6f) }
        }
        private fun tree(x:Float,y:Float,z:Float){
            box(x,y+.8f,z,.22f,1.6f,.22f,.24f,.14f,.08f)
            sphere(x,y+2.0f,z,1.15f,1.4f,1.0f,.10f,.25f,.10f)
            sphere(x+.35f,y+2.45f,z+.15f,.8f,1.0f,.8f,.12f,.31f,.13f)
        }
        private fun drawSoldier(t:Float){
            val bob=sin(t*1.7f)*.025f; val aim=sin(t*.65f)*.35f
            // boots / legs / torso / head
            box(-.28f,.38f+bob,0,.20f,.75f,.24f,.07f,.08f,.08f); box(.28f,.38f+bob,0,.20f,.75f,.24f,.07f,.08f,.08f)
            box(0f,1.18f+bob,0,.68f,.82f,.38f,.10f,.16f,.22f)
            sphere(0f,1.82f+bob,0,.29f,.34f,.29f,.48f,.34f,.24f)
            // helmet
            sphere(0f,2.02f+bob,0,.34f,.18f,.34f,.08f,.10f,.11f)
            box(-.52f,1.28f+bob,aim*.12f,.16f,.72f,.16f,.10f,.15f,.20f)
            box(.52f,1.28f+bob,-aim*.12f,.16f,.72f,.16f,.10f,.15f,.20f)
            // rifle
            box(.05f,1.18f+bob,.58f,.10f,.10f,1.05f,.08f,.08f,.07f)
            box(.05f,1.28f+bob,.18f,.13f,.13f,.38f,.14f,.15f,.16f)
        }
        private fun drawJeep(t:Float){
            val z=-5f-t*.32f
            box(0f,.62f,z,2.15f,.52f,3.2f,.08f,.12f,.12f)
            box(0f,1.08f,z+.35f,1.8f,.62f,1.55f,.05f,.08f,.06f)
            for(x in listOf(-1.05f,1.05f)) for(zz in listOf(z-1.0f,z+1.0f)) cyl(x,.35f,zz,.42f,.22f,0f,0f,90f,.025f,.025f,.025f)
            box(0f,1.1f,z+.33f,1.55f,.48f,.08f,.20f,.28f,.32f)
        }
        private fun drawDriver(t:Float){
            val z=-4.45f-t*.32f
            if(t<13f) return
            sphere(-.55f,1.47f,z,.22f,.27f,.22f,.48f,.34f,.24f)
            box(-.55f,.98f,z,.45f,.65f,.35f,.10f,.16f,.22f)
            box(-.78f,.48f,z,.16f,.55f,.18f,.07f,.08f,.08f); box(-.32f,.48f,z,.16f,.55f,.18f,.07f,.08f,.08f)
        }
        private fun drawPassenger(t:Float){
            val z=-4.45f-t*.32f
            if(t<13f) return
            sphere(.55f,1.47f,z,.22f,.27f,.22f,.72f,.52f,.40f)
            box(.55f,.98f,z,.45f,.65f,.35f,.22f,.20f,.24f)
            // casual outfit: top + skirt
            box(.55f,.62f,z,.52f,.22f,.40f,.12f,.13f,.15f)
            box(.55f,.42f,z,.58f,.22f,.44f,.16f,.12f,.15f)
            box(.38f,.15f,z,.15f,.35f,.16f,.55f,.40f,.32f); box(.72f,.15f,z,.15f,.35f,.16f,.55f,.40f,.32f)
        }
        private fun ground(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,r:Float,g:Float,b:Float)=box(x,y,z,sx,sy,sz,r,g,b)
        private fun box(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,r:Float,g:Float,b:Float){ drawMesh(cube,x,y,z,sx,sy,sz,r,g,b) }
        private fun sphere(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,r:Float,g:Float,b:Float){ drawMesh(sphere,x,y,z,sx,sy,sz,r,g,b) }
        private fun cyl(x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,rx:Float,ry:Float,rz:Float,r:Float,g:Float,b:Float){
            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,y,z); Matrix.rotateM(model,0,rx,1f,0f,0f); Matrix.rotateM(model,0,ry,0f,1f,0f); Matrix.rotateM(model,0,rz,0f,0f,1f); Matrix.scaleM(model,0,sx,sy,sz)
            Matrix.multiplyMM(vp,0,proj,0,view,0); val mvp=FloatArray(16); Matrix.multiplyMM(mvp,0,vp,0,model,0); shader.draw(cyl,mvp,r,g,b)
        }
        private fun drawMesh(m:Mesh,x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,r:Float,g:Float,b:Float){
            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,y,z); Matrix.scaleM(model,0,sx,sy,sz)
            Matrix.multiplyMM(vp,0,proj,0,view,0); val mvp=FloatArray(16); Matrix.multiplyMM(mvp,0,vp,0,model,0); shader.draw(m,mvp,r,g,b)
        }
    }

    class Mesh(private val v:FloatArray, private val n:FloatArray, private val idx:ShortArray){
        private val vb=java.nio.ByteBuffer.allocateDirect(v.size*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply{put(v).position(0)}
        private val ib=java.nio.ByteBuffer.allocateDirect(idx.size*2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().apply{put(idx).position(0)}
        fun draw(a:Int){ vb.position(0); GLES20.glVertexAttribPointer(a,3,GLES20.GL_FLOAT,false,0,vb); GLES20.glEnableVertexAttribArray(a); GLES20.glDrawElements(GLES20.GL_TRIANGLES,idx.size,GLES20.GL_UNSIGNED_SHORT,ib); GLES20.glDisableVertexAttribArray(a)}
        companion object{
            fun cube():Mesh{
                val v=floatArrayOf(-1f,-1f,-1f,1f,-1f,-1f,1f,1f,-1f,-1f,1f,-1f,-1f,-1f,1f,1f,-1f,1f,1f,1f,1f,-1f,1f,1f)
                val idx=shortArrayOf(0,1,2,2,3,0,1,5,6,6,2,1,5,4,7,7,6,5,4,0,3,3,7,4,3,2,6,6,7,3,4,5,1,1,0,4)
                return Mesh(v,FloatArray(v.size),idx)
            }
            fun sphere(lat:Int,lon:Int):Mesh{
                val vs=ArrayList<Float>(); val isx=ArrayList<Short>()
                for(i in 0..lat){val a=PI*i/lat; val y=cos(a).toFloat(); val rr=sin(a).toFloat(); for(j in 0..lon){val b=2*PI*j/lon; vs.add((rr*cos(b)).toFloat());vs.add(y);vs.add((rr*sin(b)).toFloat())}}
                for(i in 0 until lat)for(j in 0 until lon){val a=(i*(lon+1)+j).toShort();val b=(a+lon+1).toShort();isx.add(a);isx.add(b);isx.add((a+1).toShort());isx.add((a+1).toShort());isx.add(b);isx.add((b+1).toShort())}
                return Mesh(vs.toFloatArray(),FloatArray(vs.size),isx.toShortArray())
            }
            fun cylinder(seg:Int):Mesh{
                val vs=ArrayList<Float>(); for(i in 0 until seg){val a=2*PI*i/seg;vs.add(cos(a).toFloat());vs.add(-1f);vs.add(sin(a).toFloat());vs.add(cos(a).toFloat());vs.add(1f);vs.add(sin(a).toFloat())}
                val ix=ArrayList<Short>();for(i in 0 until seg){val n=((i+1)%seg)*2;val p=i*2;ix.add(p.toShort());ix.add(n.toShort());ix.add((p+1).toShort());ix.add((p+1).toShort());ix.add(n.toShort());ix.add((n+1).toShort())}
                return Mesh(vs.toFloatArray(),FloatArray(vs.size),ix.toShortArray())
            }
        }
    }

    class Shader{
        private val p:Int; private val a:Int; private val c:Int
        init{
            val vs="""attribute vec4 aPosition; uniform mat4 uMvp; void main(){gl_Position=uMvp*aPosition;}"""
            val fs="""precision mediump float; uniform vec3 uColor; void main(){gl_FragColor=vec4(uColor,1.0);}"""
            fun compile(t:Int,s:String):Int{val x=GLES20.glCreateShader(t);GLES20.glShaderSource(x,s);GLES20.glCompileShader(x);return x}
            val v=compile(GLES20.GL_VERTEX_SHADER,vs);val f=compile(GLES20.GL_FRAGMENT_SHADER,fs);p=GLES20.glCreateProgram();GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);a=GLES20.glGetAttribLocation(p,"aPosition");c=GLES20.glGetUniformLocation(p,"uColor")
        }
        fun draw(m:Mesh,mvp:FloatArray,r:Float,g:Float,b:Float){GLES20.glUseProgram(p);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p,"uMvp"),1,false,mvp,0);GLES20.glUniform3f(c,r,g,b);m.draw(a)}
    }
}
