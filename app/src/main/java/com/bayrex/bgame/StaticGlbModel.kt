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
 * Small GLES2 glTF/GLB runtime used by BGame for static environment/enemy meshes.
 * It intentionally expands indexed primitives to triangles, which keeps the renderer
 * compatible with GLES2 devices and avoids requiring an index-width extension.
 */
class StaticGlbModel(
    assets: AssetManager,
    path: String,
    private val scale: Float = 1f,
    private val onlyMeshes: Set<Int>? = null
) {
    private data class Part(
        val vertices: FloatBuffer,
        val vertexCount: Int,
        val texture: Int,
        val color: FloatArray
    )

    private val parts = ArrayList<Part>()
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
        val binStart = jsonStart + jsonLen + 8
        val binLen = ByteBuffer.wrap(bytes, jsonStart + jsonLen, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val bin = bytes.copyOfRange(binStart, min(bytes.size, binStart + binLen))

        val accessors = json.optJSONArray("accessors") ?: JSONArray()
        val views = json.optJSONArray("bufferViews") ?: JSONArray()
        val meshes = json.optJSONArray("meshes") ?: JSONArray()
        val images = json.optJSONArray("images") ?: JSONArray()
        val textures = json.optJSONArray("textures") ?: JSONArray()
        val materials = json.optJSONArray("materials") ?: JSONArray()
        val textureCache = HashMap<Int, Int>()

        fun readImage(index: Int): Int {
            if (index < 0 || index >= images.length()) return 0
            textureCache[index]?.let { return it }
            val im = images.getJSONObject(index)
            val viewIndex = im.optInt("bufferView", -1)
            if (viewIndex < 0) return 0
            val view = views.getJSONObject(viewIndex)
            val off = view.optInt("byteOffset", 0)
            val len = view.getInt("byteLength")
            if (off < 0 || off + len > bin.size) return 0
            val bitmap = BitmapFactory.decodeByteArray(bin, off, len) ?: return 0
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            bitmap.recycle()
            textureCache[index] = ids[0]
            return ids[0]
        }

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
            "MAT2" -> 4
            "MAT3" -> 9
            "MAT4" -> 16
            else -> 1
        }
        fun accessorInfo(index: Int): Triple<JSONObject, Int, Int> {
            val a = accessors.getJSONObject(index)
            val viewIndex = a.optInt("bufferView", -1)
            val view = if (viewIndex >= 0) views.getJSONObject(viewIndex) else JSONObject()
            val base = view.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            return Triple(a, base, view.optInt("byteStride", 0))
        }
        fun getFloat(accessorIndex: Int, element: Int, component: Int): Float {
            val (a, base, stride0) = accessorInfo(accessorIndex)
            val type = a.getString("type")
            val n = componentCount(type)
            val ctype = a.getInt("componentType")
            val stride = if (stride0 > 0) stride0 else n * componentSize(ctype)
            val p = base + element * stride + component * componentSize(ctype)
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            return when (ctype) {
                5126 -> bb.getFloat(p)
                5120 -> bb.get(p).toFloat()
                5121 -> bb.get(p).toUByte().toFloat()
                5122 -> bb.getShort(p).toFloat()
                5123 -> bb.getShort(p).toInt().and(0xffff).toFloat()
                5125 -> bb.getInt(p).toLong().and(0xffffffffL).toFloat()
                else -> 0f
            }
        }
        fun getIndex(accessorIndex: Int, element: Int): Int {
            val (a, base, stride0) = accessorInfo(accessorIndex)
            val ctype = a.getInt("componentType")
            val stride = if (stride0 > 0) stride0 else componentSize(ctype)
            val p = base + element * stride
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            return when (ctype) {
                5121 -> bb.get(p).toInt() and 0xff
                5123 -> bb.getShort(p).toInt() and 0xffff
                5125 -> bb.getInt(p)
                else -> element
            }
        }

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
                vec3 lightDir = normalize(vec3(-0.35, 0.85, 0.42));
                float light = 0.30 + max(dot(normalize(vNormal), lightDir), 0.0) * 0.70;
                vec4 base = uColor;
                if (uUseTex == 1) base *= texture2D(uTex, vUv);
                gl_FragColor = vec4(base.rgb * light, base.a);
            }
        """
        fun compile(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex")

        for (mi in 0 until meshes.length()) {
            if (onlyMeshes != null && !onlyMeshes.contains(mi)) continue
            val mesh = meshes.getJSONObject(mi)
            val primitives = mesh.optJSONArray("primitives") ?: continue
            for (pi in 0 until primitives.length()) {
                val p = primitives.getJSONObject(pi)
                val attrs = p.optJSONObject("attributes") ?: continue
                val posAcc = attrs.optInt("POSITION", -1)
                if (posAcc < 0) continue
                val normalAcc = attrs.optInt("NORMAL", -1)
                val uvAcc = attrs.optInt("TEXCOORD_0", -1)
                val posCount = accessors.getJSONObject(posAcc).getInt("count")
                val indexAcc = p.optInt("indices", -1)
                val count = if (indexAcc >= 0) accessors.getJSONObject(indexAcc).getInt("count") else posCount
                val data = FloatArray(count * 8)
                for (i in 0 until count) {
                    val src = if (indexAcc >= 0) getIndex(indexAcc, i) else i
                    val po = i * 8
                    data[po] = getFloat(posAcc, src, 0) * scale
                    data[po + 1] = getFloat(posAcc, src, 1) * scale
                    data[po + 2] = getFloat(posAcc, src, 2) * scale
                    if (normalAcc >= 0) {
                        data[po + 3] = getFloat(normalAcc, src, 0)
                        data[po + 4] = getFloat(normalAcc, src, 1)
                        data[po + 5] = getFloat(normalAcc, src, 2)
                    } else {
                        data[po + 4] = 1f
                    }
                    if (uvAcc >= 0) {
                        data[po + 6] = getFloat(uvAcc, src, 0)
                        data[po + 7] = getFloat(uvAcc, src, 1)
                    }
                }
                var tex = 0
                var color = floatArrayOf(1f, 1f, 1f, 1f)
                val matIndex = p.optInt("material", -1)
                if (matIndex >= 0 && matIndex < materials.length()) {
                    val mat = materials.getJSONObject(matIndex)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness")
                    pbr?.optJSONArray("baseColorFactor")?.let { a ->
                        for (i in 0 until min(4, a.length())) color[i] = a.getDouble(i).toFloat()
                    }
                    val ti = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                    if (ti >= 0 && ti < textures.length()) {
                        val imageIndex = textures.getJSONObject(ti).optInt("source", -1)
                        tex = readImage(imageIndex)
                    }
                }
                val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                fb.put(data).position(0)
                parts.add(Part(fb, count, tex, color))
            }
        }
    }

    fun draw(vp: FloatArray, x: Float, y: Float, z: Float, yaw: Float = 0f, instanceScale: Float = 1f) {
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
        for (part in parts) {
            part.vertices.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 8 * 4, part.vertices)
            part.vertices.position(3)
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 8 * 4, part.vertices)
            part.vertices.position(6)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8 * 4, part.vertices)
            GLES20.glUniform4fv(uColor, 1, part.color, 0)
            GLES20.glUniform1i(uUseTex, if (part.texture != 0) 1 else 0)
            if (part.texture != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, part.texture)
                GLES20.glUniform1i(uTex, 0)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, part.vertexCount)
            if (part.texture != 0) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aNormal)
            GLES20.glDisableVertexAttribArray(aUv)
        }
    }
}
