package com.example.game.engine

import android.opengl.GLES30
import com.example.game.model.MaterialType
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class MaterialTexturePair(
    val diffuseTexId: Int,
    val normalRoughnessTexId: Int
)

object ProceduralTextures {

    private const val SIZE = 256

    fun generateAllMaterials(): Map<MaterialType, MaterialTexturePair> {
        val result = mutableMapOf<MaterialType, MaterialTexturePair>()
        for (mat in MaterialType.entries) {
            result[mat] = buildMaterial(mat)
        }
        return result
    }

    private fun buildMaterial(type: MaterialType): MaterialTexturePair {
        val diffuse = ByteArray(SIZE * SIZE * 4)
        val height = FloatArray(SIZE * SIZE)
        val roughness = FloatArray(SIZE * SIZE)

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val u = x.toFloat() / SIZE
                val v = y.toFloat() / SIZE
                val idx = y * SIZE + x
                val pIdx = idx * 4

                var r = 128f
                var g = 128f
                var b = 128f
                var a = 255f
                var h = 0.5f
                var rough = 0.65f

                when (type) {
                    MaterialType.WOOD_FLOOR -> {
                        val plankRows = 6f
                        val py = v * plankRows
                        val row = floor(py).toInt()
                        val fy = py - row
                        val stagger = (row % 3) * 0.37f
                        val px = (u * 2f + stagger)
                        val col = floor(px).toInt()
                        val fx = px - col

                        val seamY = if (fy < 0.035f || fy > 0.965f) 0f else 1f
                        val seamX = if (fx < 0.02f || fx > 0.98f) 0f else 1f
                        val seam = minOf(seamX, seamY)

                        val seed = hash(row * 31 + col * 17)
                        val grain = fbm(u * 14f + seed * 7f, v * 52f + seed * 3f)
                        val streak = sin((v * 80f + grain * 8f + seed * 10f)) * 0.5f + 0.5f
                        val dirt = fbm(u * 6f, v * 6f)

                        val baseR = 54f + seed * 18f + streak * 16f - (1f - dirt) * 14f
                        val baseG = 35f + seed * 11f + streak * 10f - (1f - dirt) * 10f
                        val baseB = 22f + seed * 7f + streak * 6f - (1f - dirt) * 7f

                        r = baseR * (0.28f + 0.72f * seam)
                        g = baseG * (0.28f + 0.72f * seam)
                        b = baseB * (0.28f + 0.72f * seam)

                        // Nail heads near plank ends
                        val nailDist1 = hypot(fx - 0.055f, fy - 0.25f)
                        val nailDist2 = hypot(fx - 0.055f, fy - 0.75f)
                        if (nailDist1 < 0.022f || nailDist2 < 0.022f) {
                            r = 25f; g = 24f; b = 26f
                            h = 0.75f; rough = 0.35f
                        } else {
                            h = seam * (0.45f + 0.25f * streak + 0.1f * grain)
                            rough = 0.28f + 0.34f * (1f - streak) + (1f - seam) * 0.35f
                        }
                    }

                    MaterialType.WALLPAPER -> {
                        // Faded dark Victorian damask wallpaper with wainscoting stain & peeling plaster
                        val noise = fbm(u * 10f, v * 10f)
                        val fine = fbm(u * 40f, v * 40f)
                        val patternX = sin(u * PI.toFloat() * 12f)
                        val patternY = sin(v * PI.toFloat() * 12f)
                        val damask = ((patternX * patternY) * 0.5f + 0.5f)
                        val motif = if (abs(patternX) + abs(patternY) < 0.95f) 1f else 0f

                        // Vertical aged stripe + dark teal-slate damask
                        val stripe = if (((u * 12f).toInt() % 2) == 0) 1.0f else 0.86f
                        val damp = ((noise - 0.45f) * 2.5f).coerceIn(0f, 1f)

                        r = (38f + damask * 12f + motif * 8f) * stripe - damp * 14f + fine * 8f
                        g = (50f + damask * 15f + motif * 10f) * stripe - damp * 16f + fine * 9f
                        b = (48f + damask * 13f + motif * 9f) * stripe - damp * 14f + fine * 8f

                        // Wainscot mold/grime near bottom/top edges
                        val peel = smoothstep(0.68f, 0.75f, fbm(u * 5.5f + 3.1f, v * 5.5f + 1.7f))
                        if (peel > 0.1f) {
                            val plaster = 68f + fine * 28f
                            r = lerp(r, plaster, peel * 0.75f)
                            g = lerp(g, plaster * 0.94f, peel * 0.75f)
                            b = lerp(b, plaster * 0.86f, peel * 0.75f)
                        }

                        h = 0.55f + motif * 0.08f + fine * 0.06f - peel * 0.28f
                        rough = 0.78f + peel * 0.16f - motif * 0.12f
                    }

                    MaterialType.SLATE_TILE -> {
                        val tiles = 4f
                        val tx = u * tiles
                        val ty = v * tiles
                        val ix = floor(tx).toInt()
                        val iy = floor(ty).toInt()
                        val fx = tx - ix
                        val fy = ty - iy
                        val grout = if (fx < 0.045f || fx > 0.955f || fy < 0.045f || fy > 0.955f) 0f else 1f
                        val isDark = ((ix + iy) % 2 == 0)
                        val vein = fbm(u * 16f, v * 16f)
                        val crack = abs(fbm(u * 22f + 4f, v * 22f + 2f) - 0.5f)
                        val crackMask = if (crack < 0.018f) 0.45f else 1f

                        val base = if (isDark) 32f + vein * 18f else 78f + vein * 28f
                        r = base * crackMask * (0.35f + 0.65f * grout)
                        g = (base + 2f) * crackMask * (0.35f + 0.65f * grout)
                        b = (base + 6f) * crackMask * (0.35f + 0.65f * grout)

                        h = grout * (0.6f + vein * 0.12f) * crackMask
                        rough = if (grout < 0.5f) 0.92f else (0.24f + vein * 0.25f)
                    }

                    MaterialType.OAK_DOOR -> {
                        val grain = fbm(u * 28f, v * 6f)
                        val panelX = abs(u - 0.5f)
                        val inUpperPanel = panelX < 0.34f && v in 0.12f..0.44f
                        val inLowerPanel = panelX < 0.34f && v in 0.54f..0.88f
                        val bezel = (panelX in 0.31f..0.37f && (v in 0.10f..0.46f || v in 0.52f..0.90f)) ||
                            ((v in 0.10f..0.14f || v in 0.42f..0.46f || v in 0.52f..0.56f || v in 0.86f..0.90f) && panelX < 0.37f)

                        var depth = 0.65f
                        var shade = 1.0f
                        if (bezel) {
                            depth = 0.35f
                            shade = 0.62f
                        } else if (inUpperPanel || inLowerPanel) {
                            depth = 0.80f
                            shade = 1.08f
                        }
                        r = (66f + grain * 26f) * shade
                        g = (40f + grain * 16f) * shade
                        b = (24f + grain * 10f) * shade

                        // Brass handle plate on right side
                        if (u in 0.82f..0.89f && v in 0.46f..0.56f) {
                            r = 178f; g = 140f; b = 68f
                            depth = 0.95f
                            rough = 0.22f
                        } else {
                            rough = 0.45f + (1f - grain) * 0.22f
                        }
                        h = depth + grain * 0.08f
                    }

                    MaterialType.PLASTER_CEILING -> {
                        val n1 = fbm(u * 8f, v * 8f)
                        val n2 = fbm(u * 28f, v * 28f)
                        val stain = smoothstep(0.55f, 0.85f, fbm(u * 3f + 5f, v * 3f + 2f))
                        val base = 64f + n1 * 18f + n2 * 12f - stain * 22f
                        r = base
                        g = base - 2f
                        b = base - 5f
                        h = 0.5f + n2 * 0.18f - stain * 0.1f
                        rough = 0.88f
                    }

                    MaterialType.BRICK_FIREPLACE -> {
                        val rows = 8f
                        val by = v * rows
                        val row = floor(by).toInt()
                        val fy = by - row
                        val bx = u * 4f + (row % 2) * 0.5f
                        val col = floor(bx).toInt()
                        val fx = bx - col
                        val mortar = if (fx < 0.06f || fx > 0.94f || fy < 0.10f || fy > 0.90f) 0f else 1f
                        val seed = hash(row * 19 + col * 41)
                        val grit = fbm(u * 32f, v * 32f)

                        if (mortar < 0.5f) {
                            val m = 58f + grit * 20f
                            r = m; g = m - 2f; b = m - 4f
                            h = 0.25f + grit * 0.08f
                            rough = 0.92f
                        } else {
                            r = 88f + seed * 32f + grit * 18f
                            g = 36f + seed * 14f + grit * 10f
                            b = 28f + seed * 10f + grit * 8f
                            h = 0.72f + grit * 0.16f
                            rough = 0.82f
                        }
                    }

                    MaterialType.CARPET_CRIMSON -> {
                        val weave = (sin(u * 190f) * sin(v * 190f)) * 0.5f + 0.5f
                        val border = (u < 0.12f || u > 0.88f)
                        val innerTrim = (u in 0.13f..0.16f || u in 0.84f..0.87f)
                        val dirt = fbm(u * 9f, v * 9f)
                        if (innerTrim) {
                            r = 128f - dirt * 28f
                            g = 94f - dirt * 22f
                            b = 42f - dirt * 14f
                        } else if (border) {
                            r = 46f + weave * 12f
                            g = 14f + weave * 5f
                            b = 16f + weave * 5f
                        } else {
                            val diamond = abs(((u * 6f) % 1f) - 0.5f) + abs(((v * 10f) % 1f) - 0.5f)
                            val pat = if (diamond < 0.35f) 1.25f else 1.0f
                            r = (76f * pat + weave * 18f) * (0.75f + 0.25f * dirt)
                            g = (18f * pat + weave * 6f) * (0.75f + 0.25f * dirt)
                            b = (22f * pat + weave * 6f) * (0.75f + 0.25f * dirt)
                        }
                        h = 0.45f + weave * 0.22f
                        rough = 0.92f
                    }

                    MaterialType.RUSTED_METAL -> {
                        val brushed = fbm(u * 40f, v * 8f)
                        val rust = smoothstep(0.45f, 0.72f, fbm(u * 9f + 2f, v * 9f + 6f))
                        r = lerp(66f + brushed * 24f, 92f + brushed * 22f, rust)
                        g = lerp(68f + brushed * 24f, 44f + brushed * 16f, rust)
                        b = lerp(74f + brushed * 24f, 28f + brushed * 10f, rust)
                        h = 0.55f + brushed * 0.15f - rust * 0.15f
                        rough = lerp(0.26f, 0.82f, rust)
                    }

                    MaterialType.MOON_WINDOW -> {
                        val frame = (u < 0.08f || u > 0.92f || v < 0.08f || v > 0.92f ||
                            abs(u - 0.5f) < 0.04f || abs(v - 0.33f) < 0.03f || abs(v - 0.66f) < 0.03f)
                        if (frame) {
                            r = 22f; g = 20f; b = 22f
                            h = 0.85f; rough = 0.55f
                        } else {
                            val glow = 1.0f - (hypot(u - 0.5f, v - 0.42f) * 1.1f).coerceIn(0f, 0.7f)
                            val mist = fbm(u * 8f, v * 8f)
                            r = 72f * glow + mist * 24f
                            g = 104f * glow + mist * 32f
                            b = 148f * glow + mist * 44f
                            h = 0.3f; rough = 0.15f
                        }
                    }

                    MaterialType.GRANDMA_SKIN -> {
                        val wrinkle = sin(v * 70f + fbm(u * 15f, v * 15f) * 5f) * 0.5f + 0.5f
                        val mottled = fbm(u * 12f, v * 12f)
                        r = 142f + wrinkle * 14f - mottled * 24f
                        g = 128f + wrinkle * 12f - mottled * 26f
                        b = 114f + wrinkle * 10f - mottled * 22f
                        h = 0.5f + wrinkle * 0.18f
                        rough = 0.62f
                    }

                    MaterialType.GRANDMA_DRESS -> {
                        val fold = sin(u * 26f + fbm(u * 8f, v * 8f) * 3f) * 0.5f + 0.5f
                        val floral = if (sin(u * 38f) * cos(v * 38f) > 0.62f) 1f else 0f
                        val dirt = fbm(u * 10f, v * 10f)
                        r = (36f + fold * 14f + floral * 24f) * (0.7f + 0.3f * dirt)
                        g = (32f + fold * 12f + floral * 14f) * (0.7f + 0.3f * dirt)
                        b = (38f + fold * 14f + floral * 16f) * (0.7f + 0.3f * dirt)
                        h = 0.45f + fold * 0.25f
                        rough = 0.88f
                    }
                }

                diffuse[pIdx] = r.coerceIn(0f, 255f).toInt().toByte()
                diffuse[pIdx + 1] = g.coerceIn(0f, 255f).toInt().toByte()
                diffuse[pIdx + 2] = b.coerceIn(0f, 255f).toInt().toByte()
                diffuse[pIdx + 3] = a.toInt().toByte()

                height[idx] = h
                roughness[idx] = rough.coerceIn(0.08f, 0.98f)
            }
        }

        // Convert heightfield to tangent-space normal map + roughness in alpha
        val normalData = ByteArray(SIZE * SIZE * 4)
        val strength = when (type) {
            MaterialType.WOOD_FLOOR, MaterialType.BRICK_FIREPLACE, MaterialType.OAK_DOOR -> 4.2f
            MaterialType.SLATE_TILE, MaterialType.WALLPAPER, MaterialType.RUSTED_METAL -> 3.0f
            else -> 2.0f
        }

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val xm = (x - 1 + SIZE) % SIZE
                val xp = (x + 1) % SIZE
                val ym = (y - 1 + SIZE) % SIZE
                val yp = (y + 1) % SIZE

                val dX = (height[y * SIZE + xp] - height[y * SIZE + xm]) * strength
                val dY = (height[yp * SIZE + x] - height[ym * SIZE + x]) * strength

                var nx = -dX
                var ny = -dY
                var nz = 1.0f
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= len; ny /= len; nz /= len

                val pIdx = (y * SIZE + x) * 4
                normalData[pIdx] = ((nx * 0.5f + 0.5f) * 255f).coerceIn(0f, 255f).toInt().toByte()
                normalData[pIdx + 1] = ((ny * 0.5f + 0.5f) * 255f).coerceIn(0f, 255f).toInt().toByte()
                normalData[pIdx + 2] = ((nz * 0.5f + 0.5f) * 255f).coerceIn(0f, 255f).toInt().toByte()
                normalData[pIdx + 3] = (roughness[y * SIZE + x] * 255f).coerceIn(0f, 255f).toInt().toByte()
            }
        }

        val diffTex = uploadTexture(diffuse, srgb = false)
        val normTex = uploadTexture(normalData, srgb = false)
        return MaterialTexturePair(diffTex, normTex)
    }

    private fun uploadTexture(rgba: ByteArray, srgb: Boolean): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)

        val buf = ByteBuffer.allocateDirect(rgba.size)
        buf.put(rgba)
        buf.position(0)

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            if (srgb) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA8,
            SIZE,
            SIZE,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buf
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return texId
    }

    private fun hash(n: Int): Float {
        var x = n
        x = (x shl 13) xor x
        val t = x * (x * x * 15731 + 789221) + 1376312589
        return 1.0f - ((t and 0x7fffffff) / 1073741824.0f) * 0.5f
    }

    private fun hash2(ix: Int, iy: Int): Float {
        return hash(ix * 57 + iy * 131)
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)

        val a = hash2(ix, iy)
        val b = hash2(ix + 1, iy)
        val c = hash2(ix, iy + 1)
        val d = hash2(ix + 1, iy + 1)

        return lerp(lerp(a, b, ux), lerp(c, d, ux), uy)
    }

    private fun fbm(x: Float, y: Float): Float {
        var v = 0f
        var amp = 0.5f
        var fx = x
        var fy = y
        for (i in 0 until 4) {
            v += amp * valueNoise(fx, fy)
            fx *= 2.02f
            fy *= 2.02f
            amp *= 0.5f
        }
        return v
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hypot(a: Float, b: Float) = sqrt(a * a + b * b)
}
