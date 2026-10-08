package com.bayrex.bgame

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.*

/**
 * GLES2 animated GLB renderer for the BGame runner.
 * The uploaded model contains a skeletal animation named "Running".
 * Skinning is done on the CPU so it works on old GLES2 phones without
 * requiring a large joint-matrix uniform array.
 */
class RunningGlbModel(
    assets: AssetManager,
    path: String,
    private val scale: Float = 0.01f
) {
    private data class Vertex(
        val p: FloatArray,
        val n: FloatArray,
        val uv: FloatArray,
        val joints: IntArray,
        val weights: FloatArray
    )
    private data class Part(
        val vertices: Array<Vertex>,
        val texture: Int,
        val color: FloatArray
    )
    private data class Node(
        val t: FloatArray,
        val r: FloatArray,
        val s: FloatArray,
        val children: IntArray,
        val mesh: Int,
        val skin: Int
    )
    private data class Channel(
        val sampler: Int,
        val node: Int,
        val path: String
    )
    private data class Sampler(
        val input: Int,
        val output: Int,
        val interpolation: String
    )

    private val parts = ArrayList<Part>()
    private val nodes = ArrayList<Node>()
    private val parents: IntArray
    private val channels = ArrayList<Channel>()
    private val samplers = ArrayList<Sampler>()
    private val accessors: JSONArray
    private val views: JSONArray
    private val bin: ByteArray
    private val inverseBind = ArrayList<FloatArray>()
    private val joints: IntArray
    private val local = Array(128) { FloatArray(16) }
    private val world = Array(128) { FloatArray(16) }
    private val skinMatrices = Array(128) { FloatArray(16) }
    private val animatedT = Array(128) { FloatArray(3) }
    private val animatedR = Array(128) { floatArrayOf(0f, 0f, 0f, 1f) }
    private val animatedS = Array(128) { floatArrayOf(1f, 1f, 1f) }
    private val vertexData: Array<FloatArray>
    private var animationDuration = 0.81666666f

    private val program: Int
    private val aPos: Int
    private val aNormal: Int
    private val aUv: Int
    private val uMvp: Int
    private val uModel: Int
    private val uColor: Int
    private val uTex: Int
    private val uUseTex: Int

    init {
        val bytes = assets.open(path).use { it.readBytes() }
        require(bytes.size >= 20) { "Invalid GLB: $path" }
        val jsonLen = ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val jsonStart = 20
        val json = JSONObject(String(bytes, jsonStart, jsonLen, StandardCharsets.UTF_8).trimEnd('\u0000', ' '))
        val binHeader = jsonStart + jsonLen
        val binLen = ByteBuffer.wrap(bytes, binHeader, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val binStart = binHeader + 8
        bin = bytes.copyOfRange(binStart, min(bytes.size, binStart + binLen))

        accessors = json.optJSONArray("accessors") ?: JSONArray()
        views = json.optJSONArray("bufferViews") ?: JSONArray()
        val meshes = json.optJSONArray("meshes") ?: JSONArray()
        val images = json.optJSONArray("images") ?: JSONArray()
        val textures = json.optJSONArray("textures") ?: JSONArray()
        val materials = json.optJSONArray("materials") ?: JSONArray()
        val textureCache = HashMap<Int, Int>()

        fun componentSize(type: Int) = when (type) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            5125, 5126 -> 4
            else -> 4
        }
        fun componentCount(type: String) = when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT4" -> 16
            else -> 1
        }
        fun info(index: Int): Triple<JSONObject, Int, Int> {
            val a = accessors.getJSONObject(index)
            val vi = a.optInt("bufferView", -1)
            val v = if (vi >= 0) views.getJSONObject(vi) else JSONObject()
            return Triple(a, v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0), v.optInt("byteStride", 0))
        }
        fun number(accessor: Int, element: Int, component: Int): Float {
            val a = accessors.getJSONObject(accessor)
            val vi = a.optInt("bufferView", -1)
            val v = if (vi >= 0) views.getJSONObject(vi) else JSONObject()
            val base = v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            val n = componentCount(a.getString("type"))
            val ct = a.getInt("componentType")
            val stride = if (v.optInt("byteStride", 0) > 0) v.optInt("byteStride", 0) else n * componentSize(ct)
            val p = base + element * stride + component * componentSize(ct)
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            return when (ct) {
                5126 -> bb.getFloat(p)
                5125 -> bb.getInt(p).toLong().and(0xffffffffL).toFloat()
                5123 -> bb.getShort(p).toInt().and(0xffff).toFloat()
                5122 -> bb.getShort(p).toFloat()
                5121 -> bb.get(p).toInt().and(0xff).toFloat()
                5120 -> bb.get(p).toFloat()
                else -> 0f
            }
        }
        fun indexValue(accessor: Int, element: Int): Int {
            val a = accessors.getJSONObject(accessor)
            val vi = a.optInt("bufferView", -1)
            val v = views.getJSONObject(vi)
            val base = v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            val ct = a.getInt("componentType")
            val stride = if (v.optInt("byteStride", 0) > 0) v.optInt("byteStride", 0) else componentSize(ct)
            val p = base + element * stride
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            return when (ct) {
                5121 -> bb.get(p).toInt() and 255
                5123 -> bb.getShort(p).toInt() and 65535
                5125 -> bb.getInt(p)
                else -> element
            }
        }
        fun image(index: Int): Int {
            if (index < 0 || index >= images.length()) return 0
            textureCache[index]?.let { return it }
            val im = images.getJSONObject(index)
            val vi = im.optInt("bufferView", -1)
            if (vi < 0) return 0
            val v = views.getJSONObject(vi)
            val off = v.optInt("byteOffset", 0)
            val len = v.getInt("byteLength")
            if (off < 0 || off + len > bin.size) return 0
            val bmp = BitmapFactory.decodeByteArray(bin, off, len) ?: return 0
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            bmp.recycle()
            textureCache[index] = id[0]
            return id[0]
        }

        val nodeJson = json.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodeJson.length()) {
            val n = nodeJson.getJSONObject(i)
            val t = FloatArray(3) { k -> n.optJSONArray("translation")?.optDouble(k, 0.0)?.toFloat() ?: 0f }
            val r = FloatArray(4) { k -> n.optJSONArray("rotation")?.optDouble(k, if (k == 3) 1.0 else 0.0)?.toFloat() ?: if (k == 3) 1f else 0f }
            val s = FloatArray(3) { k -> n.optJSONArray("scale")?.optDouble(k, 1.0)?.toFloat() ?: 1f }
            val ca = n.optJSONArray("children")
            val children = IntArray(ca?.length() ?: 0) { ca!!.getInt(it) }
            nodes.add(Node(t, r, s, children, n.optInt("mesh", -1), n.optInt("skin", -1)))
        }
        parents = IntArray(nodes.size) { -1 }
        for (i in nodes.indices) for (c in nodes[i].children) parents[c] = i

        val skins = json.optJSONArray("skins") ?: JSONArray()
        val skin = skins.optJSONObject(0) ?: JSONObject()
        val jointArray = skin.optJSONArray("joints") ?: JSONArray()
        joints = IntArray(jointArray.length()) { jointArray.getInt(it) }
        val ibAcc = skin.optInt("inverseBindMatrices", -1)
        if (ibAcc >= 0) {
            val count = accessors.getJSONObject(ibAcc).getInt("count")
            for (i in 0 until count) {
                val m = FloatArray(16)
                for (k in 0 until 16) m[k] = number(ibAcc, i, k)
                inverseBind.add(m)
            }
        }
        while (inverseBind.size < joints.size) {
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            inverseBind.add(m)
        }

        val anim = json.optJSONArray("animations")?.optJSONObject(0)
        if (anim != null) {
            val sa = anim.optJSONArray("samplers") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val s = sa.getJSONObject(i)
                samplers.add(Sampler(s.getInt("input"), s.getInt("output"), s.optString("interpolation", "LINEAR")))
            }
            val ca = anim.optJSONArray("channels") ?: JSONArray()
            for (i in 0 until ca.length()) {
                val c = ca.getJSONObject(i)
                val target = c.getJSONObject("target")
                channels.add(Channel(c.getInt("sampler"), target.getInt("node"), target.getString("path")))
            }
            for (i in samplers.indices) {
                val s = samplers[i]
                val a = accessors.getJSONObject(s.input)
                animationDuration = max(animationDuration, a.optJSONArray("max")?.optDouble(0, animationDuration)?.toFloat() ?: animationDuration)
            }
        }

        val partsTemp = ArrayList<Part>()
        for (mi in 0 until meshes.length()) {
            val mesh = meshes.getJSONObject(mi)
            val ps = mesh.optJSONArray("primitives") ?: continue
            for (pi in 0 until ps.length()) {
                val p = ps.getJSONObject(pi)
                val attrs = p.optJSONObject("attributes") ?: continue
                val pos = attrs.optInt("POSITION", -1)
                val nor = attrs.optInt("NORMAL", -1)
                val uv = attrs.optInt("TEXCOORD_0", -1)
                val ji = attrs.optInt("JOINTS_0", -1)
                val wi = attrs.optInt("WEIGHTS_0", -1)
                if (pos < 0 || ji < 0 || wi < 0) continue
                val pc = accessors.getJSONObject(pos).getInt("count")
                val ia = p.optInt("indices", -1)
                val count = if (ia >= 0) accessors.getJSONObject(ia).getInt("count") else pc
                val verts = Array(count) {
                    val src = if (ia >= 0) indexValue(ia, it) else it
                    val pp = FloatArray(3) { k -> number(pos, src, k) }
                    val nn = FloatArray(3) { k -> if (nor >= 0) number(nor, src, k) else if (k == 1) 1f else 0f }
                    val uu = FloatArray(2) { k -> if (uv >= 0) number(uv, src, k) else 0f }
                    val jj = IntArray(4) { k -> number(ji, src, k).toInt() }
                    val ww = FloatArray(4) { k -> number(wi, src, k) }
                    Vertex(pp, nn, uu, jj, ww)
                }
                var tex = 0
                val color = floatArrayOf(1f, 1f, 1f, 1f)
                val matIndex = p.optInt("material", -1)
                if (matIndex >= 0 && matIndex < materials.length()) {
                    val mat = materials.getJSONObject(matIndex)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness")
                    pbr?.optJSONArray("baseColorFactor")?.let { a ->
                        for (k in 0 until min(4, a.length())) color[k] = a.getDouble(k).toFloat()
                    }
                    val ti = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                    if (ti >= 0 && ti < textures.length()) tex = image(textures.getJSONObject(ti).optInt("source", -1))
                }
                partsTemp.add(Part(verts, tex, color))
            }
        }
        parts.addAll(partsTemp)
        vertexData = Array(parts.sumOf { it.vertices.size }) { FloatArray(8) }

        val vs = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            varying vec3 vNormal;
            varying vec2 vUv;
            void main() {
                vNormal = normalize(mat3(uModel) * aNormal);
                vUv = aUv;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        val fs = """
            precision mediump float;
            uniform vec4 uColor;
            uniform sampler2D uTex;
            uniform int uUseTex;
            varying vec3 vNormal;
            varying vec2 vUv;
            void main() {
                float light = 0.30 + max(dot(normalize(vNormal), normalize(vec3(-0.35,0.85,0.42))),0.0)*0.70;
                vec4 base = uColor;
                if (uUseTex == 1) base *= texture2D(uTex,vUv);
                gl_FragColor = vec4(base.rgb*light,base.a);
            }
        """
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v); GLES20.glAttachShader(program, f); GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex")
    }

    fun draw(vp: FloatArray, x: Float, y: Float, z: Float, time: Float, yaw: Float = 180f, instanceScale: Float = 1f) {
        animate(time)
        val mdl = FloatArray(16)
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mdl, 0)
        Matrix.translateM(mdl, 0, x, y, z)
        Matrix.rotateM(mdl, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(mdl, 0, instanceScale, instanceScale, instanceScale)
        Matrix.multiplyMM(mvp, 0, vp, 0, mdl, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, mdl, 0)

        var outIndex = 0
        for (part in parts) {
            val data = vertexData
            val fb = ByteBuffer.allocateDirect(part.vertices.size * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (v in part.vertices) {
                val p = FloatArray(4)
                val n = FloatArray(4)
                p[3] = 1f; n[3] = 0f
                for (k in 0..2) {
                    p[k] = v.p[k]; n[k] = v.n[k]
                }
                val sp = FloatArray(3)
                val sn = FloatArray(3)
                var total = 0f
                for (k in 0..3) {
                    val w = v.weights[k]
                    if (w <= 0f || v.joints[k] < 0 || v.joints[k] >= skinMatrices.size) continue
                    total += w
                    val sm = skinMatrices[v.joints[k]]
                    val qx = sm[0]*p[0] + sm[4]*p[1] + sm[8]*p[2] + sm[12]
                    val qy = sm[1]*p[0] + sm[5]*p[1] + sm[9]*p[2] + sm[13]
                    val qz = sm[2]*p[0] + sm[6]*p[1] + sm[10]*p[2] + sm[14]
                    sp[0] += qx*w; sp[1] += qy*w; sp[2] += qz*w
                    val nx = sm[0]*n[0] + sm[4]*n[1] + sm[8]*n[2]
                    val ny = sm[1]*n[0] + sm[5]*n[1] + sm[9]*n[2]
                    val nz = sm[2]*n[0] + sm[6]*n[1] + sm[10]*n[2]
                    sn[0] += nx*w; sn[1] += ny*w; sn[2] += nz*w
                }
                if (total <= 0f) { sp[0]=p[0]; sp[1]=p[1]; sp[2]=p[2]; sn[0]=n[0]; sn[1]=n[1]; sn[2]=n[2] }
                val o = outIndex++
                data[o][0]=sp[0]*scale; data[o][1]=sp[1]*scale; data[o][2]=sp[2]*scale
                val nl = sqrt(sn[0]*sn[0]+sn[1]*sn[1]+sn[2]*sn[2]).coerceAtLeast(0.0001f)
                data[o][3]=sn[0]/nl; data[o][4]=sn[1]/nl; data[o][5]=sn[2]/nl
                data[o][6]=v.uv[0]; data[o][7]=v.uv[1]
            }
            fb.put(data, outIndex - part.vertices.size, part.vertices.size).position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos,3,GLES20.GL_FLOAT,false,32,fb)
            fb.position(3); GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal,3,GLES20.GL_FLOAT,false,32,fb)
            fb.position(6); GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv,2,GLES20.GL_FLOAT,false,32,fb)
            GLES20.glUniform4fv(uColor,1,part.color,0)
            GLES20.glUniform1i(uUseTex,if(part.texture!=0)1 else 0)
            if(part.texture!=0){ GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,part.texture); GLES20.glUniform1i(uTex,0) }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,part.vertices.size)
            if(part.texture!=0) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aNormal); GLES20.glDisableVertexAttribArray(aUv)
        }
    }

    private fun animate(time: Float) {
        for (i in nodes.indices) {
            animatedT[i][0]=nodes[i].t[0]; animatedT[i][1]=nodes[i].t[1]; animatedT[i][2]=nodes[i].t[2]
            animatedR[i][0]=nodes[i].r[0]; animatedR[i][1]=nodes[i].r[1]; animatedR[i][2]=nodes[i].r[2]; animatedR[i][3]=nodes[i].r[3]
            animatedS[i][0]=nodes[i].s[0]; animatedS[i][1]=nodes[i].s[1]; animatedS[i][2]=nodes[i].s[2]
        }
        val t = if(animationDuration>0f) ((time % animationDuration)+animationDuration)%animationDuration else 0f
        for(c in channels){
            if(c.sampler !in samplers.indices || c.node !in nodes.indices) continue
            val s=samplers[c.sampler]
            val a=accessors.getJSONObject(s.input)
            val count=a.getInt("count")
            if(count<=0) continue
            val first=number(s.input,0,0)
            val last=number(s.input,count-1,0)
            var lo=0; var hi=count-1
            if(t<=first) hi=0 else if(t>=last) lo=count-1 else {
                while(hi-lo>1){ val mid=(lo+hi)/2; if(number(s.input,mid,0)<=t) lo=mid else hi=mid }
            }
            val ta=number(s.input,lo,0); val tb=number(s.input,hi,0)
            val f=if(hi==lo)0f else ((t-ta)/(tb-ta)).coerceIn(0f,1f)
            val outAcc=accessors.getJSONObject(s.output)
            val comps=when(outAcc.getString("type")){"VEC4"->4;"VEC3"->3;else->1}
            val vals=FloatArray(comps){k->number(s.output,lo,k)}
            val vals2=FloatArray(comps){k->number(s.output,hi,k)}
            val result=FloatArray(comps){k->vals[k]+(vals2[k]-vals[k])*f}
            when(c.path){
                "translation" -> for(k in 0..2) animatedT[c.node][k]=result[k]
                "scale" -> for(k in 0..2) animatedS[c.node][k]=result[k]
                "rotation" -> {
                    var qx=result[0]; var qy=result[1]; var qz=result[2]; var qw=result[3]
                    val ql=sqrt(qx*qx+qy*qy+qz*qz+qw*qw).coerceAtLeast(0.0001f)
                    qx/=ql;qy/=ql;qz/=ql;qw/=ql
                    animatedR[c.node][0]=qx;animatedR[c.node][1]=qy;animatedR[c.node][2]=qz;animatedR[c.node][3]=qw
                }
            }
        }
        for(i in nodes.indices) buildWorld(i)
        for(j in joints.indices){
            val joint=joints[j]
            Matrix.multiplyMM(skinMatrices[j],0,world[joint],0,inverseBind[j],0)
        }
        // Vertex JOINTS_0 values index the skin joint palette, not node ids.
        for(i in parts.indices) {
            // no-op: palette lookup is handled in draw through skinMatrices.
        }
    }

    private fun buildWorld(i:Int){
        Matrix.setIdentityM(local[i],0)
        Matrix.translateM(local[i],0,animatedT[i][0],animatedT[i][1],animatedT[i][2])
        val q=animatedR[i]
        val qx=q[0]; val qy=q[1]; val qz=q[2]; val qw=q[3]
        val xx=qx*qx; val yy=qy*qy; val zz=qz*qz; val xy=qx*qy; val xz=qx*qz; val yz=qy*qz; val wx=qw*qx; val wy=qw*qy; val wz=qw*qz
        val rm=FloatArray(16)
        rm[0]=1f-2f*(yy+zz);rm[1]=2f*(xy+wz);rm[2]=2f*(xz-wy);rm[3]=0f
        rm[4]=2f*(xy-wz);rm[5]=1f-2f*(xx+zz);rm[6]=2f*(yz+wx);rm[7]=0f
        rm[8]=2f*(xz+wy);rm[9]=2f*(yz-wx);rm[10]=1f-2f*(xx+yy);rm[11]=0f
        rm[12]=0f;rm[13]=0f;rm[14]=0f;rm[15]=1f
        Matrix.multiplyMM(local[i],0,local[i],0,rm,0)
        Matrix.scaleM(local[i],0,animatedS[i][0],animatedS[i][1],animatedS[i][2])
        val p=parents[i]
        if(p>=0) Matrix.multiplyMM(world[i],0,world[p],0,local[i],0) else System.arraycopy(local[i],0,world[i],0,16)
    }
}
