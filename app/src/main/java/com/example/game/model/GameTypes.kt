package com.example.game.model

import kotlin.math.sqrt

data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun lengthSq(): Float = x * x + y * y + z * z

    fun horizontalDistanceTo(other: Vec3): Float {
        val dx = x - other.x
        val dz = z - other.z
        return sqrt(dx * dx + dz * dz)
    }

    fun distanceTo(other: Vec3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-5f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 0f)
    }
}

data class AABB(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float
) {
    fun containsXZ(x: Float, z: Float, margin: Float = 0f): Boolean {
        return x >= minX - margin && x <= maxX + margin &&
            z >= minZ - margin && z <= maxZ + margin
    }

    fun overlapsVertical(footY: Float, headY: Float): Boolean {
        return headY > minY + 0.05f && footY < maxY - 0.05f
    }
}

enum class MaterialType(val id: Int) {
    WOOD_FLOOR(0),
    WALLPAPER(1),
    SLATE_TILE(2),
    OAK_DOOR(3),
    PLASTER_CEILING(4),
    BRICK_FIREPLACE(5),
    CARPET_CRIMSON(6),
    RUSTED_METAL(7),
    MOON_WINDOW(8),
    GRANDMA_SKIN(9),
    GRANDMA_DRESS(10)
}

enum class EscapeItemType(
    val displayName: String,
    val hint: String
) {
    CROWBAR("Palanca de Hierro", "Quita los tablones de la puerta principal"),
    BRASS_KEY("Llave de Bronce", "Abre el candado oxidado de la salida"),
    MASTER_KEY("Llave Maestra", "Destraba el cerrojo final de la puerta")
}

data class CollectibleItem(
    val id: String,
    val type: EscapeItemType,
    val position: Vec3,
    val rotationY: Float = 0f,
    var collected: Boolean = false
)

enum class DoorOrientation {
    ALONG_X, // Wall runs along X, doorway opens along Z
    ALONG_Z  // Wall runs along Z, doorway opens along X
}

data class DoorState(
    val id: String,
    val hingePos: Vec3,
    val width: Float,
    val height: Float,
    val orientation: DoorOrientation,
    var openAmount: Float = 0f, // 0f = closed, 1f = 90 deg open
    var targetOpen: Float = 0f,
    val swingSign: Float = 1f
) {
    fun isMostlyOpen(): Boolean = openAmount > 0.6f

    fun centerPos(): Vec3 {
        val half = width * 0.5f
        return when (orientation) {
            DoorOrientation.ALONG_X -> Vec3(hingePos.x + half * swingSign, hingePos.y + height * 0.5f, hingePos.z)
            DoorOrientation.ALONG_Z -> Vec3(hingePos.x, hingePos.y + height * 0.5f, hingePos.z + half * swingSign)
        }
    }

    fun currentAABB(): AABB {
        val angle = openAmount * (Math.PI.toFloat() * 0.48f) * swingSign
        val cosA = kotlin.math.cos(angle)
        val sinA = kotlin.math.sin(angle)
        val thickness = 0.08f
        val endX: Float
        val endZ: Float
        when (orientation) {
            DoorOrientation.ALONG_X -> {
                endX = hingePos.x + width * cosA * swingSign
                endZ = hingePos.z + width * sinA * swingSign
            }
            DoorOrientation.ALONG_Z -> {
                endX = hingePos.x - width * sinA * swingSign
                endZ = hingePos.z + width * cosA * swingSign
            }
        }
        val minX = minOf(hingePos.x, endX) - thickness
        val maxX = maxOf(hingePos.x, endX) + thickness
        val minZ = minOf(hingePos.z, endZ) - thickness
        val maxZ = maxOf(hingePos.z, endZ) + thickness
        return AABB(minX, hingePos.y, minZ, maxX, hingePos.y + height, maxZ)
    }
}

data class Wardrobe(
    val id: String,
    val position: Vec3,
    val facingYawDeg: Float,
    val hideSpot: Vec3,
    val exitSpot: Vec3,
    var doorOpenAmount: Float = 0f
)

data class PointLight(
    val position: Vec3,
    val color: Vec3,
    val baseIntensity: Float,
    val radius: Float,
    val flickerSpeed: Float = 0f,
    val flickerAmount: Float = 0f
)

enum class Difficulty(
    val label: String,
    val grandmaPatrolSpeed: Float,
    val grandmaChaseSpeed: Float,
    val hearingMultiplier: Float,
    val visionRange: Float
) {
    NORMAL("Normal", 1.75f, 3.05f, 1.0f, 10.5f),
    HARD("Difícil", 2.15f, 3.55f, 1.25f, 13.0f),
    NIGHTMARE("Pesadilla", 2.55f, 4.05f, 1.55f, 16.0f)
}

enum class GrandmaState {
    PATROL,
    INVESTIGATE,
    CHASE,
    SEARCH
}

enum class GameScreenState {
    TITLE,
    PLAYING,
    PAUSED,
    CAUGHT,
    ESCAPED
}

data class ExitDoorProgress(
    val planksRemoved: Boolean = false,
    val padlockUnlocked: Boolean = false,
    val deadboltUnlocked: Boolean = false,
    var openProgress: Float = 0f
) {
    val unlockedSteps: Int
        get() = (if (planksRemoved) 1 else 0) +
            (if (padlockUnlocked) 1 else 0) +
            (if (deadboltUnlocked) 1 else 0)

    val isFullyUnlocked: Boolean
        get() = planksRemoved && padlockUnlocked && deadboltUnlocked
}

data class HudState(
    val elapsedSeconds: Float = 0f,
    val playerFloor: Int = 2,
    val grandmaFloor: Int = 1,
    val isFlashlightOn: Boolean = true,
    val isCrouching: Boolean = false,
    val isSprinting: Boolean = false,
    val isHiding: Boolean = false,
    val stamina: Float = 1f,
    val dangerProximity: Float = 0f, // 0f safe -> 1f imminent capture
    val grandmaChasing: Boolean = false,
    val interactionPrompt: String? = null,
    val canInteract: Boolean = false,
    val collectedItems: List<EscapeItemType> = emptyList(),
    val exitProgress: ExitDoorProgress = ExitDoorProgress(),
    val statusBanner: String? = null,
    val noteOverlayText: String? = null,
    val timesSpotted: Int = 0
)
