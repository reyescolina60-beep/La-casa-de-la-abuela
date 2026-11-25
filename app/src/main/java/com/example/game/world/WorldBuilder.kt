package com.example.game.world

import android.opengl.GLES30
import com.example.game.model.MaterialType
import com.example.game.model.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MeshBuffer(
    val vaoId: Int,
    val vboId: Int,
    val vertexCount: Int
)

class StaticBatch(
    val material: MaterialType,
    val tint: Vec3,
    val emissive: Vec3,
    val mesh: MeshBuffer
)

class GrandmaMeshes(
    val skirt: MeshBuffer,
    val torso: MeshBuffer,
    val shawl: MeshBuffer,
    val head: MeshBuffer,
    val hairBun: MeshBuffer,
    val eyes: MeshBuffer,
    val arm: MeshBuffer,
    val leg: MeshBuffer,
    val cane: MeshBuffer
)

class WorldMeshes(
    val staticBatches: List<StaticBatch>,
    val unitBox: MeshBuffer,
    val doorPanel: MeshBuffer,
    val grandma: GrandmaMeshes,
    val dustPointsVbo: Int,
    val dustPointsVao: Int,
    val dustCount: Int
)

object WorldBuilder {

    // Vertex layout: pos(3) + normal(3) + tangent(3) + uv(2) + ao(1) = 12 floats
    private const val FLOATS_PER_VERTEX = 12
    private const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    fun buildWorldMeshes(house: HouseMap): WorldMeshes {
        // Group static boxes by (MaterialType, tint, emissive) for minimal draw calls
        data class BatchKey(val mat: MaterialType, val tint: Vec3, val emissive: Vec3)
        val grouped = LinkedHashMap<BatchKey, MutableList<BoxVisual>>()
        for (box in house.staticBoxes) {
            val key = BatchKey(box.material, box.tint, box.emissive)
            grouped.getOrPut(key) { mutableListOf() }.add(box)
        }

        val batches = mutableListOf<StaticBatch>()
        for ((key, boxes) in grouped) {
            val floats = ArrayList<Float>(boxes.size * 36 * FLOATS_PER_VERTEX)
            for (b in boxes) {
                appendTexturedBox(
                    floats,
                    b.min.x, b.min.y, b.min.z,
                    b.max.x, b.max.y, b.max.z,
                    b.uvScale,
                    isRoomBox = b.solid
                )
            }
            batches.add(StaticBatch(key.mat, key.tint, key.emissive, uploadMesh(floats)))
        }

        // Unit box [0..1] centered in X/Z or [-0.5..0.5]
        val unitList = ArrayList<Float>(36 * FLOATS_PER_VERTEX)
        appendTexturedBox(unitList, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 1.0f, isRoomBox = false)
        val unitBox = uploadMesh(unitList)

        // Door leaf mesh anchored at hinge (0,0,0) extending +Z (for ALONG_Z) from 0..1, Y 0..1, thickness X [-0.04..0.04]
        val doorList = ArrayList<Float>(36 * FLOATS_PER_VERTEX)
        appendTexturedBox(doorList, -0.04f, 0f, 0f, 0.04f, 1f, 1f, 1.0f, isRoomBox = false, stretchUVToFace = true)
        val doorPanel = uploadMesh(doorList)

        val grandma = buildGrandmaMeshes()

        // Dust particles across both floors
        val dustCount = 260
        val dustCoords = FloatArray(dustCount * 3)
        val rng = java.util.Random(42L)
        for (i in 0 until dustCount) {
            dustCoords[i * 3] = -9.4f + rng.nextFloat() * 18.8f
            val floorOffset = if (i % 2 == 0) 0f else 3.6f
            dustCoords[i * 3 + 1] = floorOffset + 0.35f + rng.nextFloat() * 2.5f
            dustCoords[i * 3 + 2] = -7.4f + rng.nextFloat() * 14.8f
        }
        val vaos = IntArray(1)
        val vbos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(1, vbos, 0)
        GLES30.glBindVertexArray(vaos[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        val dBuf = floatArrayToBuffer(dustCoords)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, dustCoords.size * 4, dBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)

        return WorldMeshes(batches, unitBox, doorPanel, grandma, vbos[0], vaos[0], dustCount)
    }

    private fun buildGrandmaMeshes(): GrandmaMeshes {
        // Tapered tattered skirt (y 0.0 -> 0.85)
        val skirt = buildTaperedCylinder(0.28f, 0.16f, 0.85f, 14)
        // Hunched upper torso (y 0.0 -> 0.62)
        val torso = buildTaperedCylinder(0.17f, 0.19f, 0.62f, 12)
        // Shawl collar over shoulders
        val shawl = buildTaperedCylinder(0.23f, 0.13f, 0.28f, 12)
        // Gaunt head (ellipsoid)
        val head = buildEllipsoid(0.125f, 0.155f, 0.145f, 12, 10)
        // Hair bun
        val hairBun = buildEllipsoid(0.068f, 0.068f, 0.068f, 8, 8)
        // Glowing eyes (pair of tiny spheres)
        val eyeFloats = ArrayList<Float>()
        appendEllipsoid(eyeFloats, -0.044f, 0.02f, 0.122f, 0.018f, 0.014f, 0.018f, 8, 6)
        appendEllipsoid(eyeFloats, 0.044f, 0.02f, 0.122f, 0.018f, 0.014f, 0.018f, 8, 6)
        val eyes = uploadMesh(eyeFloats)

        // Arm / Leg / Cane anchored at top pivot (0,0,0) extending downward -Y
        val armList = ArrayList<Float>()
        appendTaperedCylinderOffset(armList, 0f, -0.58f, 0f, 0.042f, 0.052f, 0.58f, 10)
        val arm = uploadMesh(armList)

        val legList = ArrayList<Float>()
        appendTaperedCylinderOffset(legList, 0f, -0.75f, 0f, 0.055f, 0.068f, 0.75f, 10)
        val leg = uploadMesh(legList)

        val caneList = ArrayList<Float>()
        appendTaperedCylinderOffset(caneList, 0f, -0.88f, 0f, 0.022f, 0.026f, 0.88f, 8)
        // Crook handle at top of cane
        appendTexturedBox(caneList, -0.025f, -0.04f, -0.025f, 0.025f, 0.03f, 0.12f, 1f, isRoomBox = false)
        val cane = uploadMesh(caneList)

        return GrandmaMeshes(skirt, torso, shawl, head, hairBun, eyes, arm, leg, cane)
    }

    private fun buildTaperedCylinder(rBottom: Float, rTop: Float, height: Float, segs: Int): MeshBuffer {
        val list = ArrayList<Float>()
        appendTaperedCylinderOffset(list, 0f, 0f, 0f, rBottom, rTop, height, segs)
        return uploadMesh(list)
    }

    private fun appendTaperedCylinderOffset(
        out: MutableList<Float>,
        ox: Float, oy: Float, oz: Float,
        rBottom: Float, rTop: Float, height: Float, segs: Int
    ) {
        for (i in 0 until segs) {
            val a0 = (i.toFloat() / segs) * (2f * PI.toFloat())
            val a1 = ((i + 1).toFloat() / segs) * (2f * PI.toFloat())
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)

            val p00 = Vec3(ox + c0 * rBottom, oy, oz + s0 * rBottom)
            val p10 = Vec3(ox + c1 * rBottom, oy, oz + s1 * rBottom)
            val p11 = Vec3(ox + c1 * rTop, oy + height, oz + s1 * rTop)
            val p01 = Vec3(ox + c0 * rTop, oy + height, oz + s0 * rTop)

            val n0 = Vec3(c0, 0.1f, s0).normalized()
            val n1 = Vec3(c1, 0.1f, s1).normalized()
            val t0 = Vec3(-s0, 0f, c0).normalized()
            val t1 = Vec3(-s1, 0f, c1).normalized()

            val u0 = i.toFloat() / segs
            val u1 = (i + 1).toFloat() / segs

            pushVert(out, p00, n0, t0, u0, 1f, 0.82f)
            pushVert(out, p10, n1, t1, u1, 1f, 0.82f)
            pushVert(out, p11, n1, t1, u1, 0f, 0.95f)

            pushVert(out, p00, n0, t0, u0, 1f, 0.82f)
            pushVert(out, p11, n1, t1, u1, 0f, 0.95f)
            pushVert(out, p01, n0, t0, u0, 0f, 0.95f)
        }
    }

    private fun buildEllipsoid(rx: Float, ry: Float, rz: Float, slices: Int, stacks: Int): MeshBuffer {
        val list = ArrayList<Float>()
        appendEllipsoid(list, 0f, 0f, 0f, rx, ry, rz, slices, stacks)
        return uploadMesh(list)
    }

    private fun appendEllipsoid(
        out: MutableList<Float>,
        ox: Float, oy: Float, oz: Float,
        rx: Float, ry: Float, rz: Float,
        slices: Int, stacks: Int
    ) {
        for (st in 0 until stacks) {
            val v0 = st.toFloat() / stacks
            val v1 = (st + 1).toFloat() / stacks
            val phi0 = (v0 - 0.5f) * PI.toFloat()
            val phi1 = (v1 - 0.5f) * PI.toFloat()

            for (sl in 0 until slices) {
                val u0 = sl.toFloat() / slices
                val u1 = (sl + 1).toFloat() / slices
                val th0 = u0 * 2f * PI.toFloat()
                val th1 = u1 * 2f * PI.toFloat()

                fun vert(phi: Float, th: Float): Pair<Vec3, Vec3> {
                    val cp = cos(phi); val sp = sin(phi)
                    val ct = cos(th); val st2 = sin(th)
                    val p = Vec3(ox + rx * cp * ct, oy + ry * sp, oz + rz * cp * st2)
                    val n = Vec3(cp * ct / rx, sp / ry, cp * st2 / rz).normalized()
                    return p to n
                }

                val (p00, n00) = vert(phi0, th0)
                val (p10, n10) = vert(phi0, th1)
                val (p11, n11) = vert(phi1, th1)
                val (p01, n01) = vert(phi1, th0)
                val t = Vec3(1f, 0f, 0f)

                pushVert(out, p00, n00, t, u0, v0, 0.95f)
                pushVert(out, p10, n10, t, u1, v0, 0.95f)
                pushVert(out, p11, n11, t, u1, v1, 0.95f)

                pushVert(out, p00, n00, t, u0, v0, 0.95f)
                pushVert(out, p11, n11, t, u1, v1, 0.95f)
                pushVert(out, p01, n01, t, u0, v1, 0.95f)
            }
        }
    }

    private fun appendTexturedBox(
        out: MutableList<Float>,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        uvScale: Float,
        isRoomBox: Boolean,
        stretchUVToFace: Boolean = false
    ) {
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ

        fun uv(w: Float, h: Float): Pair<Float, Float> =
            if (stretchUVToFace) 1f to 1f else (w * uvScale) to (h * uvScale)

        val botAO = if (isRoomBox) 0.58f else 0.78f
        val topAO = if (isRoomBox) 0.85f else 1.0f

        // +Z Front
        run {
            val (u, v) = uv(dx, dy)
            val n = Vec3(0f, 0f, 1f); val t = Vec3(1f, 0f, 0f)
            pushQuad(out,
                Vec3(minX, minY, maxZ), Vec3(maxX, minY, maxZ),
                Vec3(maxX, maxY, maxZ), Vec3(minX, maxY, maxZ),
                n, t, u, v, botAO, topAO
            )
        }
        // -Z Back
        run {
            val (u, v) = uv(dx, dy)
            val n = Vec3(0f, 0f, -1f); val t = Vec3(-1f, 0f, 0f)
            pushQuad(out,
                Vec3(maxX, minY, minZ), Vec3(minX, minY, minZ),
                Vec3(minX, maxY, minZ), Vec3(maxX, maxY, minZ),
                n, t, u, v, botAO, topAO
            )
        }
        // +X Right
        run {
            val (u, v) = uv(dz, dy)
            val n = Vec3(1f, 0f, 0f); val t = Vec3(0f, 0f, -1f)
            pushQuad(out,
                Vec3(maxX, minY, maxZ), Vec3(maxX, minY, minZ),
                Vec3(maxX, maxY, minZ), Vec3(maxX, maxY, maxZ),
                n, t, u, v, botAO, topAO
            )
        }
        // -X Left
        run {
            val (u, v) = uv(dz, dy)
            val n = Vec3(-1f, 0f, 0f); val t = Vec3(0f, 0f, 1f)
            pushQuad(out,
                Vec3(minX, minY, minZ), Vec3(minX, minY, maxZ),
                Vec3(minX, maxY, maxZ), Vec3(minX, maxY, minZ),
                n, t, u, v, botAO, topAO
            )
        }
        // +Y Top
        run {
            val (u, v) = uv(dx, dz)
            val n = Vec3(0f, 1f, 0f); val t = Vec3(1f, 0f, 0f)
            pushQuad(out,
                Vec3(minX, maxY, maxZ), Vec3(maxX, maxY, maxZ),
                Vec3(maxX, maxY, minZ), Vec3(minX, maxY, minZ),
                n, t, u, v, 0.96f, 0.96f
            )
        }
        // -Y Bottom
        run {
            val (u, v) = uv(dx, dz)
            val n = Vec3(0f, -1f, 0f); val t = Vec3(1f, 0f, 0f)
            pushQuad(out,
                Vec3(minX, minY, minZ), Vec3(maxX, minY, minZ),
                Vec3(maxX, minY, maxZ), Vec3(minX, minY, maxZ),
                n, t, u, v, 0.85f, 0.85f
            )
        }
    }

    private fun pushQuad(
        out: MutableList<Float>,
        p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3,
        n: Vec3, t: Vec3,
        uMax: Float, vMax: Float,
        aoBottom: Float, aoTop: Float
    ) {
        pushVert(out, p0, n, t, 0f, vMax, aoBottom)
        pushVert(out, p1, n, t, uMax, vMax, aoBottom)
        pushVert(out, p2, n, t, uMax, 0f, aoTop)

        pushVert(out, p0, n, t, 0f, vMax, aoBottom)
        pushVert(out, p2, n, t, uMax, 0f, aoTop)
        pushVert(out, p3, n, t, 0f, 0f, aoTop)
    }

    private fun pushVert(
        out: MutableList<Float>,
        p: Vec3, n: Vec3, t: Vec3,
        u: Float, v: Float, ao: Float
    ) {
        out.add(p.x); out.add(p.y); out.add(p.z)
        out.add(n.x); out.add(n.y); out.add(n.z)
        out.add(t.x); out.add(t.y); out.add(t.z)
        out.add(u); out.add(v); out.add(ao)
    }

    private fun uploadMesh(floats: List<Float>): MeshBuffer {
        val arr = floats.toFloatArray()
        val vaos = IntArray(1)
        val vbos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(1, vbos, 0)

        GLES30.glBindVertexArray(vaos[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        val buf = floatArrayToBuffer(arr)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, arr.size * 4, buf, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 6 * 4)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, 9 * 4)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, STRIDE_BYTES, 11 * 4)

        GLES30.glBindVertexArray(0)
        return MeshBuffer(vaos[0], vbos[0], arr.size / FLOATS_PER_VERTEX)
    }

    private fun floatArrayToBuffer(arr: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(arr.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(arr)
        fb.position(0)
        return fb
    }
}
