package com.example.game.world

import com.example.game.model.*
import kotlin.math.abs
import kotlin.random.Random

data class NavNode(
    val id: Int,
    val pos: Vec3,
    val floor: Int,
    val neighbors: MutableList<Int> = mutableListOf()
)

data class BoxVisual(
    val min: Vec3,
    val max: Vec3,
    val material: MaterialType,
    val uvScale: Float = 1f,
    val tint: Vec3 = Vec3(1f, 1f, 1f),
    val emissive: Vec3 = Vec3(0f, 0f, 0f),
    val solid: Boolean = true
)

class HouseMap {

    companion object {
        const val FLOOR_1_Y = 0.0f
        const val FLOOR_1_CEIL_Y = 3.2f
        const val FLOOR_2_Y = 3.6f
        const val FLOOR_2_CEIL_Y = 6.8f
        const val WALL_THICKNESS = 0.26f

        val PLAYER_SPAWN = Vec3(-7.0f, FLOOR_2_Y, 5.4f)
        const val PLAYER_SPAWN_YAW = 105f // Looking toward nightstand & bedroom door
        val GRANDMA_SPAWN = Vec3(-6.5f, FLOOR_1_Y, -4.5f)
        val EXIT_DOOR_POS = Vec3(0f, FLOOR_1_Y + 1.2f, 7.85f)
        val NOTE_POS = Vec3(-5.6f, FLOOR_2_Y + 0.72f, 6.9f)
    }

    val staticBoxes = mutableListOf<BoxVisual>()
    val colliders = mutableListOf<AABB>()
    val doors = mutableListOf<DoorState>()
    val wardrobes = mutableListOf<Wardrobe>()
    val items = mutableListOf<CollectibleItem>()
    val pointLights = mutableListOf<PointLight>()
    val navNodes = mutableListOf<NavNode>()
    var exitProgress = ExitDoorProgress()
    var noteRead = false

    init {
        buildArchitecture()
        buildFurnitureAndLights()
        buildDoors()
        buildWardrobes()
        spawnEscapeItems()
        buildNavGraph()
    }

    fun resetForNewRun() {
        doors.forEach {
            it.openAmount = 0f
            it.targetOpen = 0f
        }
        wardrobes.forEach { it.doorOpenAmount = 0f }
        exitProgress = ExitDoorProgress()
        noteRead = false
        items.clear()
        spawnEscapeItems()
    }

    private fun addSolidBox(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        mat: MaterialType,
        uvScale: Float = 0.5f,
        tint: Vec3 = Vec3(1f, 1f, 1f),
        emissive: Vec3 = Vec3(0f, 0f, 0f),
        addCollider: Boolean = true
    ) {
        staticBoxes.add(
            BoxVisual(
                Vec3(minX, minY, minZ),
                Vec3(maxX, maxY, maxZ),
                mat,
                uvScale,
                tint,
                emissive,
                addCollider
            )
        )
        if (addCollider) {
            colliders.add(AABB(minX, minY, minZ, maxX, maxY, maxZ))
        }
    }

    private fun buildArchitecture() {
        // --- FLOOR 1 SLAB ---
        addSolidBox(-10f, -0.25f, -8f, -3f, 0f, 8f, MaterialType.WOOD_FLOOR, 0.6f)
        addSolidBox(-3f, -0.25f, -8f, 3f, 0f, 8f, MaterialType.SLATE_TILE, 0.55f)
        addSolidBox(3f, -0.25f, -8f, 10f, 0f, 8f, MaterialType.WOOD_FLOOR, 0.6f)

        // Foyer Crimson Runner Carpet (non-solid decorative layer)
        addSolidBox(-1.1f, 0.005f, 1.0f, 1.1f, 0.02f, 7.5f, MaterialType.CARPET_CRIMSON, 0.8f, addCollider = false)
        // Upper Gallery Carpet
        addSolidBox(-2.6f, FLOOR_2_Y + 0.005f, 2.0f, 2.6f, FLOOR_2_Y + 0.02f, 7.3f, MaterialType.CARPET_CRIMSON, 0.7f, addCollider = false)

        // --- FLOOR 2 SLAB (with stairwell cutout x in [-1.45, +1.45], z in [-5.5, +1.5]) ---
        // Left wing (West rooms + West upper gallery strip)
        addSolidBox(-10f, FLOOR_1_CEIL_Y, -8f, -1.45f, FLOOR_2_Y, 8f, MaterialType.WOOD_FLOOR, 0.6f)
        // Bathroom Slate Tile Floor overlay
        addSolidBox(-9.86f, FLOOR_2_Y - 0.02f, -7.86f, -3.14f, FLOOR_2_Y + 0.01f, -0.14f, MaterialType.SLATE_TILE, 0.6f, addCollider = false)
        // Right wing (East rooms + East upper gallery strip)
        addSolidBox(1.45f, FLOOR_1_CEIL_Y, -8f, 10f, FLOOR_2_Y, 8f, MaterialType.WOOD_FLOOR, 0.6f)
        // North Landing above Back Hall (z in [-8, -5.5], x in [-1.45, +1.45])
        addSolidBox(-1.45f, FLOOR_1_CEIL_Y, -8f, 1.45f, FLOOR_2_Y, -5.5f, MaterialType.WOOD_FLOOR, 0.6f)
        // South Upper Overlook Balcony (z in [1.5, 8], x in [-1.45, +1.45])
        addSolidBox(-1.45f, FLOOR_1_CEIL_Y, 1.5f, 1.45f, FLOOR_2_Y, 8f, MaterialType.WOOD_FLOOR, 0.6f)

        // --- CEILING (Floor 2 Ceiling) ---
        addSolidBox(-10f, FLOOR_2_CEIL_Y, -8f, 10f, FLOOR_2_CEIL_Y + 0.25f, 8f, MaterialType.PLASTER_CEILING, 0.4f)

        // Exposed dark oak ceiling beams on both floors for extra depth & realism
        for (z in listOf(-6.5f, -2.5f, 2.5f, 6.2f)) {
            addSolidBox(-9.8f, FLOOR_1_CEIL_Y - 0.16f, z - 0.12f, -3.1f, FLOOR_1_CEIL_Y, z + 0.12f, MaterialType.OAK_DOOR, 0.5f, addCollider = false)
            addSolidBox(3.1f, FLOOR_1_CEIL_Y - 0.16f, z - 0.12f, 9.8f, FLOOR_1_CEIL_Y, z + 0.12f, MaterialType.OAK_DOOR, 0.5f, addCollider = false)
            addSolidBox(-9.8f, FLOOR_2_CEIL_Y - 0.16f, z - 0.12f, 9.8f, FLOOR_2_CEIL_Y, z + 0.12f, MaterialType.OAK_DOOR, 0.5f, addCollider = false)
        }

        // --- GRAND STAIRCASE (x in [-1.38, 1.38], z from +0.5 (y=0) to -5.5 (y=3.6)) ---
        val numSteps = 24
        val stepDepth = 6.0f / numSteps
        val stepHeight = FLOOR_2_Y / numSteps
        for (i in 0 until numSteps) {
            val zFront = 0.5f - i * stepDepth
            val zBack = zFront - stepDepth - 0.02f
            val topY = (i + 1) * stepHeight
            addSolidBox(-1.38f, maxOf(0f, topY - 0.24f), zBack, 1.38f, topY, zFront, MaterialType.WOOD_FLOOR, 0.9f, addCollider = false)
            // Crimson stair runner center strip
            addSolidBox(-0.62f, topY - 0.01f, zBack, 0.62f, topY + 0.015f, zFront, MaterialType.CARPET_CRIMSON, 1.0f, addCollider = false)
        }
        // Solid paneled enclosure under stairs on Floor 1 (prevents clipping, leaves side corridors open!)
        addSolidBox(-1.42f, 0f, -5.5f, 1.42f, 2.95f, -0.35f, MaterialType.OAK_DOOR, 0.5f, addCollider = true)
        // Side stair stringers / walls
        addSolidBox(-1.45f, 0f, -5.5f, -1.35f, FLOOR_2_Y + 0.95f, 0.55f, MaterialType.OAK_DOOR, 0.5f, addCollider = false)
        addSolidBox(1.35f, 0f, -5.5f, 1.45f, FLOOR_2_Y + 0.95f, 0.55f, MaterialType.OAK_DOOR, 0.5f, addCollider = false)

        // Floor 2 Stairwell Balustrade Railings (visible balusters + solid invisible barrier)
        buildBalustradeX(-1.45f, -5.5f, 1.5f, FLOOR_2_Y)
        buildBalustradeX(1.45f, -5.5f, 1.5f, FLOOR_2_Y)
        buildBalustradeZ(-1.45f, 1.45f, 1.5f, FLOOR_2_Y)

        // --- OUTER PERIMETER WALLS (Full height 0 .. 6.8) ---
        val hT = WALL_THICKNESS * 0.5f
        // North Outer Wall (z = -8)
        addSolidBox(-10f - hT, 0f, -8f - hT, 10f + hT, FLOOR_2_CEIL_Y, -8f + hT, MaterialType.WALLPAPER, 0.45f)
        // West Outer Wall (x = -10)
        addSolidBox(-10f - hT, 0f, -8f - hT, -10f + hT, FLOOR_2_CEIL_Y, 8f + hT, MaterialType.WALLPAPER, 0.45f)
        // East Outer Wall (x = +10)
        addSolidBox(10f - hT, 0f, -8f - hT, 10f + hT, FLOOR_2_CEIL_Y, 8f + hT, MaterialType.WALLPAPER, 0.45f)

        // South Outer Wall (z = +8) — with Main Exit Door opening at x in [-0.85, +0.85], y in [0, 2.45]
        addSolidBox(-10f - hT, 0f, 8f - hT, -0.85f, FLOOR_2_CEIL_Y, 8f + hT, MaterialType.WALLPAPER, 0.45f)
        addSolidBox(0.85f, 0f, 8f - hT, 10f + hT, FLOOR_2_CEIL_Y, 8f + hT, MaterialType.WALLPAPER, 0.45f)
        // Lintel above Exit Door (y from 2.45 to 6.8)
        addSolidBox(-0.85f, 2.45f, 8f - hT, 0.85f, FLOOR_2_CEIL_Y, 8f + hT, MaterialType.WALLPAPER, 0.45f)
        // Exit Door Frame Trim
        addSolidBox(-0.92f, 0f, 7.82f, -0.82f, 2.48f, 8.05f, MaterialType.OAK_DOOR, 0.6f, addCollider = false)
        addSolidBox(0.82f, 0f, 7.82f, 0.92f, 2.48f, 8.05f, MaterialType.OAK_DOOR, 0.6f, addCollider = false)
        addSolidBox(-0.92f, 2.40f, 7.82f, 0.92f, 2.52f, 8.05f, MaterialType.OAK_DOOR, 0.6f, addCollider = false)

        // Exterior Moonlit Porch beyond the Exit Door (visible when door opens!)
        addSolidBox(-3.5f, -0.25f, 8.1f, 3.5f, 0f, 14.5f, MaterialType.SLATE_TILE, 0.5f, tint = Vec3(0.45f, 0.55f, 0.72f), addCollider = false)
        addSolidBox(-3.5f, 0f, 13.8f, 3.5f, 5.5f, 14.2f, MaterialType.MOON_WINDOW, 0.3f, emissive = Vec3(0.18f, 0.28f, 0.45f), addCollider = false)

        // --- INNER CORRIDOR WALLS (x = -3.0 and x = +3.0) on BOTH FLOORS ---
        buildCorridorWallWithDoors(-3.0f, FLOOR_1_Y, FLOOR_1_CEIL_Y)
        buildCorridorWallWithDoors(-3.0f, FLOOR_2_Y, FLOOR_2_CEIL_Y)
        buildCorridorWallWithDoors(3.0f, FLOOR_1_Y, FLOOR_1_CEIL_Y)
        buildCorridorWallWithDoors(3.0f, FLOOR_2_Y, FLOOR_2_CEIL_Y)

        // --- WING DIVIDER WALLS (z = 0.0) on BOTH FLOORS ---
        // West Wing (x from -10 to -3, archway opening x in [-7.2, -5.8])
        buildWingWall(-10f, -3f, -7.2f, -5.8f, FLOOR_1_Y, FLOOR_1_CEIL_Y)
        buildWingWall(-10f, -3f, -7.2f, -5.8f, FLOOR_2_Y, FLOOR_2_CEIL_Y)
        // East Wing (x from +3 to +10, archway opening x in [+5.8, +7.2])
        buildWingWall(3f, 10f, 5.8f, 7.2f, FLOOR_1_Y, FLOOR_1_CEIL_Y)
        buildWingWall(3f, 10f, 5.8f, 7.2f, FLOOR_2_Y, FLOOR_2_CEIL_Y)

        // --- MOONLIT ARCHED WINDOWS ---
        // Floor 2 South Overlook Window
        addSolidBox(-0.9f, FLOOR_2_Y + 0.75f, 7.82f, 0.9f, FLOOR_2_Y + 2.75f, 7.90f, MaterialType.MOON_WINDOW, 1f, emissive = Vec3(0.25f, 0.36f, 0.56f), addCollider = false)
        // Floor 2 Master Bedroom West Window
        addSolidBox(-9.86f, FLOOR_2_Y + 0.85f, 3.3f, -9.80f, FLOOR_2_Y + 2.55f, 5.0f, MaterialType.MOON_WINDOW, 1f, emissive = Vec3(0.20f, 0.30f, 0.48f), addCollider = false)
        // Floor 2 Guest Room East Window
        addSolidBox(9.80f, FLOOR_2_Y + 0.85f, 3.3f, 9.86f, FLOOR_2_Y + 2.55f, 5.0f, MaterialType.MOON_WINDOW, 1f, emissive = Vec3(0.20f, 0.30f, 0.48f), addCollider = false)
        // Floor 1 Living Room & Dining Room Windows
        addSolidBox(-7.4f, 0.9f, 7.82f, -5.6f, 2.5f, 7.90f, MaterialType.MOON_WINDOW, 1f, emissive = Vec3(0.18f, 0.27f, 0.44f), addCollider = false)
        addSolidBox(5.6f, 0.9f, 7.82f, 7.4f, 2.5f, 7.90f, MaterialType.MOON_WINDOW, 1f, emissive = Vec3(0.18f, 0.27f, 0.44f), addCollider = false)
    }

    private fun buildBalustradeX(x: Float, zStart: Float, zEnd: Float, floorY: Float) {
        // Invisible collision rail so player cannot fall off gallery edge
        colliders.add(AABB(x - 0.14f, floorY, zStart, x + 0.14f, floorY + 1.2f, zEnd))
        // Top handrail
        addSolidBox(x - 0.07f, floorY + 0.88f, zStart, x + 0.07f, floorY + 0.98f, zEnd, MaterialType.OAK_DOOR, 0.7f, addCollider = false)
        // Bottom shoe rail
        addSolidBox(x - 0.06f, floorY, zStart, x + 0.06f, floorY + 0.10f, zEnd, MaterialType.OAK_DOOR, 0.7f, addCollider = false)
        // Baluster posts every 0.35m
        var z = zStart + 0.15f
        while (z < zEnd) {
            addSolidBox(x - 0.035f, floorY + 0.10f, z - 0.035f, x + 0.035f, floorY + 0.88f, z + 0.035f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
            z += 0.35f
        }
    }

    private fun buildBalustradeZ(xStart: Float, xEnd: Float, z: Float, floorY: Float) {
        colliders.add(AABB(xStart, floorY, z - 0.14f, xEnd, floorY + 1.2f, z + 0.14f))
        addSolidBox(xStart, floorY + 0.88f, z - 0.07f, xEnd, floorY + 0.98f, z + 0.07f, MaterialType.OAK_DOOR, 0.7f, addCollider = false)
        addSolidBox(xStart, floorY, z - 0.06f, xEnd, floorY + 0.10f, z + 0.06f, MaterialType.OAK_DOOR, 0.7f, addCollider = false)
        var x = xStart + 0.15f
        while (x < xEnd) {
            addSolidBox(x - 0.035f, floorY + 0.10f, z - 0.035f, x + 0.035f, floorY + 0.88f, z + 0.035f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
            x += 0.35f
        }
    }

    private fun buildCorridorWallWithDoors(x: Float, floorY: Float, ceilY: Float) {
        val hT = WALL_THICKNESS * 0.5f
        val doorTop = floorY + 2.30f
        // North segment: -8 to -4.9
        addSolidBox(x - hT, floorY, -8f, x + hT, ceilY, -4.9f, MaterialType.WALLPAPER, 0.45f)
        // North door lintel: -4.9 to -3.5
        addSolidBox(x - hT, doorTop, -4.9f, x + hT, ceilY, -3.5f, MaterialType.WALLPAPER, 0.45f)
        // Middle segment: -3.5 to +3.5
        addSolidBox(x - hT, floorY, -3.5f, x + hT, ceilY, 3.5f, MaterialType.WALLPAPER, 0.45f)
        // South door lintel: +3.5 to +4.9
        addSolidBox(x - hT, doorTop, 3.5f, x + hT, ceilY, 4.9f, MaterialType.WALLPAPER, 0.45f)
        // South segment: +4.9 to +8
        addSolidBox(x - hT, floorY, 4.9f, x + hT, ceilY, 8f, MaterialType.WALLPAPER, 0.45f)

        // Wooden doorframes for realism
        for (dz in listOf(-4.2f, 4.2f)) {
            addSolidBox(x - hT - 0.03f, floorY, dz - 0.74f, x + hT + 0.03f, doorTop + 0.04f, dz - 0.67f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
            addSolidBox(x - hT - 0.03f, floorY, dz + 0.67f, x + hT + 0.03f, doorTop + 0.04f, dz + 0.74f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
            addSolidBox(x - hT - 0.03f, doorTop - 0.02f, dz - 0.74f, x + hT + 0.03f, doorTop + 0.08f, dz + 0.74f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
        }
    }

    private fun buildWingWall(xMin: Float, xMax: Float, openMin: Float, openMax: Float, floorY: Float, ceilY: Float) {
        val hT = WALL_THICKNESS * 0.5f
        val doorTop = floorY + 2.35f
        addSolidBox(xMin, floorY, -hT, openMin, ceilY, hT, MaterialType.WALLPAPER, 0.45f)
        addSolidBox(openMin, doorTop, -hT, openMax, ceilY, hT, MaterialType.WALLPAPER, 0.45f)
        addSolidBox(openMax, floorY, -hT, xMax, ceilY, hT, MaterialType.WALLPAPER, 0.45f)
        // Arch trim
        addSolidBox(openMin - 0.06f, floorY, -hT - 0.03f, openMin + 0.02f, doorTop, hT + 0.03f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
        addSolidBox(openMax - 0.02f, floorY, -hT - 0.03f, openMax + 0.06f, doorTop, hT + 0.03f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
        addSolidBox(openMin - 0.06f, doorTop - 0.04f, -hT - 0.03f, openMax + 0.06f, doorTop + 0.06f, hT + 0.03f, MaterialType.OAK_DOOR, 0.8f, addCollider = false)
    }

    private fun buildFurnitureAndLights() {
        // ==========================================
        // FLOOR 2 FURNITURE (Player spawns here!)
        // ==========================================

        // Master Bedroom (x in [-10, -3], z in [0, 8])
        // Four-poster bed
        addSolidBox(-9.5f, FLOOR_2_Y, 5.2f, -7.5f, FLOOR_2_Y + 0.55f, 7.4f, MaterialType.OAK_DOOR, 0.6f) // Frame
        addSolidBox(-9.4f, FLOOR_2_Y + 0.55f, 5.3f, -7.6f, FLOOR_2_Y + 0.78f, 7.3f, MaterialType.CARPET_CRIMSON, 0.8f, tint = Vec3(0.85f, 0.8f, 0.75f)) // Mattress
        addSolidBox(-9.5f, FLOOR_2_Y, 7.25f, -7.5f, FLOOR_2_Y + 1.65f, 7.42f, MaterialType.OAK_DOOR, 0.6f) // Headboard
        // Nightstand (with starting Note!)
        addSolidBox(-6.0f, FLOOR_2_Y, 6.6f, -5.2f, FLOOR_2_Y + 0.68f, 7.35f, MaterialType.OAK_DOOR, 0.8f)
        // Bedside Warm Sconce Light
        addWallSconce(-5.6f, FLOOR_2_Y + 1.55f, 7.76f, Vec3(1.0f, 0.66f, 0.32f), 1.4f, 6.5f)
        // Dresser against South wall
        addSolidBox(-4.6f, FLOOR_2_Y, 7.1f, -3.3f, FLOOR_2_Y + 1.0f, 7.8f, MaterialType.OAK_DOOR, 0.7f)

        // Bathroom (x in [-10, -3], z in [-8, 0])
        // Vintage Clawfoot Tub
        addSolidBox(-9.6f, FLOOR_2_Y, -7.6f, -7.6f, FLOOR_2_Y + 0.68f, -5.8f, MaterialType.SLATE_TILE, 0.9f, tint = Vec3(1.1f, 1.1f, 1.1f))
        // Vanity Sink
        addSolidBox(-9.6f, FLOOR_2_Y, -2.9f, -8.8f, FLOOR_2_Y + 0.85f, -1.5f, MaterialType.OAK_DOOR, 0.8f)
        addSolidBox(-9.5f, FLOOR_2_Y + 0.85f, -2.8f, -8.9f, FLOOR_2_Y + 0.94f, -1.6f, MaterialType.SLATE_TILE, 1f, tint = Vec3(1.1f, 1.1f, 1.1f), addCollider = false)
        addWallSconce(-9.75f, FLOOR_2_Y + 1.95f, -2.2f, Vec3(0.78f, 0.88f, 1.0f), 1.05f, 6.0f)

        // Guest Room (x in [3, 10], z in [0, 8])
        addSolidBox(7.6f, FLOOR_2_Y, 5.2f, 9.6f, FLOOR_2_Y + 0.65f, 7.4f, MaterialType.OAK_DOOR, 0.7f)
        addSolidBox(7.7f, FLOOR_2_Y + 0.65f, 5.3f, 9.5f, FLOOR_2_Y + 0.82f, 7.3f, MaterialType.PLASTER_CEILING, 0.8f, tint = Vec3(0.75f, 0.68f, 0.64f))
        // Small wooden chest
        addSolidBox(4.0f, FLOOR_2_Y, 6.8f, 5.3f, FLOOR_2_Y + 0.62f, 7.65f, MaterialType.OAK_DOOR, 0.8f)
        addWallSconce(6.5f, FLOOR_2_Y + 2.15f, 7.76f, Vec3(1.0f, 0.68f, 0.35f), 1.15f, 6.2f)

        // Upper Study (x in [3, 10], z in [-8, 0])
        // Bookshelves along North & East wall
        addSolidBox(7.4f, FLOOR_2_Y, -7.8f, 9.6f, FLOOR_2_Y + 2.4f, -7.1f, MaterialType.OAK_DOOR, 0.6f)
        addSolidBox(9.1f, FLOOR_2_Y, -6.2f, 9.8f, FLOOR_2_Y + 2.4f, -3.0f, MaterialType.OAK_DOOR, 0.6f)
        // Reading Desk
        addSolidBox(5.2f, FLOOR_2_Y, -5.2f, 7.0f, FLOOR_2_Y + 0.82f, -4.1f, MaterialType.OAK_DOOR, 0.7f)
        addWallSconce(6.1f, FLOOR_2_Y + 2.1f, -7.76f, Vec3(1.0f, 0.62f, 0.28f), 1.3f, 6.8f)

        // Upper Gallery Moonlight + Grandfather Clock
        addSolidBox(-2.8f, FLOOR_2_Y, 6.8f, -2.0f, FLOOR_2_Y + 2.25f, 7.6f, MaterialType.OAK_DOOR, 0.7f)
        pointLights.add(PointLight(Vec3(0f, FLOOR_2_Y + 2.0f, 6.2f), Vec3(0.45f, 0.64f, 0.96f), 1.35f, 9.5f))
        addWallSconce(0f, FLOOR_2_Y + 2.2f, -7.76f, Vec3(1.0f, 0.64f, 0.32f), 1.35f, 7.5f)

        // ==========================================
        // FLOOR 1 FURNITURE (Grandma spawns here!)
        // ==========================================

        // Living Room (x in [-10, -3], z in [0, 8])
        // Brick Fireplace on West wall
        addSolidBox(-9.85f, 0f, 2.8f, -8.85f, 2.75f, 5.4f, MaterialType.BRICK_FIREPLACE, 0.7f)
        // Fireplace Embers Glow
        addSolidBox(-9.5f, 0.05f, 3.4f, -8.82f, 0.52f, 4.8f, MaterialType.BRICK_FIREPLACE, 1f, emissive = Vec3(0.95f, 0.28f, 0.06f), addCollider = false)
        pointLights.add(PointLight(Vec3(-8.4f, 0.7f, 4.1f), Vec3(1.0f, 0.42f, 0.14f), 1.85f, 7.5f, flickerSpeed = 7.5f, flickerAmount = 0.25f))
        // Sofa & Coffee Table
        addSolidBox(-6.9f, 0f, 3.0f, -6.0f, 0.75f, 5.2f, MaterialType.CARPET_CRIMSON, 0.8f)
        addSolidBox(-8.1f, 0f, 3.4f, -7.3f, 0.48f, 4.8f, MaterialType.OAK_DOOR, 0.8f)

        // Kitchen (x in [-10, -3], z in [-8, 0])
        // Counters along North & West wall
        addSolidBox(-9.8f, 0f, -7.8f, -5.0f, 0.92f, -6.9f, MaterialType.OAK_DOOR, 0.7f)
        addSolidBox(-9.8f, 0f, -6.9f, -8.9f, 0.92f, -2.4f, MaterialType.OAK_DOOR, 0.7f)
        // Prep Island
        addSolidBox(-7.4f, 0f, -4.6f, -5.6f, 0.90f, -3.5f, MaterialType.OAK_DOOR, 0.7f)
        addWallSconce(-6.5f, 2.25f, -7.76f, Vec3(0.95f, 0.72f, 0.42f), 1.35f, 7.2f)

        // Dining Room (x in [3, 10], z in [0, 8])
        // Long Banquet Table
        addSolidBox(5.6f, 0f, 2.7f, 7.4f, 0.84f, 5.9f, MaterialType.OAK_DOOR, 0.65f)
        // Sideboard Cabinet
        addSolidBox(9.1f, 0f, 2.6f, 9.8f, 1.05f, 5.8f, MaterialType.OAK_DOOR, 0.7f)
        addWallSconce(6.5f, 2.2f, 7.76f, Vec3(1.0f, 0.66f, 0.30f), 1.25f, 6.8f)

        // Pantry / Lower Study (x in [3, 10], z in [-8, 0])
        addSolidBox(5.8f, 0f, -6.9f, 7.8f, 0.84f, -5.8f, MaterialType.OAK_DOOR, 0.7f)
        addSolidBox(9.0f, 0f, -4.8f, 9.8f, 2.2f, -1.8f, MaterialType.OAK_DOOR, 0.6f)
        addWallSconce(6.8f, 2.2f, -7.76f, Vec3(1.0f, 0.62f, 0.28f), 1.25f, 6.8f)

        // Foyer Main Chandelier / Sconce Glow near Exit Door
        addWallSconce(-2.78f, 2.15f, 6.4f, Vec3(1.0f, 0.70f, 0.36f), 1.45f, 7.5f)
        addWallSconce(2.78f, 2.15f, 6.4f, Vec3(1.0f, 0.70f, 0.36f), 1.45f, 7.5f)
    }

    private fun addWallSconce(x: Float, y: Float, z: Float, color: Vec3, intensity: Float, radius: Float) {
        addSolidBox(x - 0.08f, y - 0.14f, z - 0.08f, x + 0.08f, y + 0.02f, z + 0.08f, MaterialType.RUSTED_METAL, 1f, addCollider = false)
        addSolidBox(x - 0.05f, y + 0.02f, z - 0.05f, x + 0.05f, y + 0.15f, z + 0.05f, MaterialType.OAK_DOOR, 1f, emissive = color * 0.9f, addCollider = false)
        pointLights.add(PointLight(Vec3(x, y + 0.12f, z), color, intensity, radius, flickerSpeed = 5.2f + abs(x), flickerAmount = 0.14f))
    }

    private fun buildDoors() {
        // Floor 2 Doors
        doors.add(DoorState("f2_bedroom", Vec3(-3.0f, FLOOR_2_Y, 3.52f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))
        doors.add(DoorState("f2_bathroom", Vec3(-3.0f, FLOOR_2_Y, -4.88f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))
        doors.add(DoorState("f2_guest", Vec3(3.0f, FLOOR_2_Y, 3.52f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))
        doors.add(DoorState("f2_study", Vec3(3.0f, FLOOR_2_Y, -4.88f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))

        // Floor 1 Doors
        doors.add(DoorState("f1_living", Vec3(-3.0f, FLOOR_1_Y, 3.52f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))
        doors.add(DoorState("f1_kitchen", Vec3(-3.0f, FLOOR_1_Y, -4.88f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))
        doors.add(DoorState("f1_dining", Vec3(3.0f, FLOOR_1_Y, 3.52f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))
        doors.add(DoorState("f1_pantry", Vec3(3.0f, FLOOR_1_Y, -4.88f), 1.36f, 2.28f, DoorOrientation.ALONG_Z, swingSign = 1f))
    }

    private fun buildWardrobes() {
        // Master Bedroom Wardrobe (Floor 2)
        addWardrobe("w_f2_bed", Vec3(-9.35f, FLOOR_2_Y, 1.3f), 90f, Vec3(-9.25f, FLOOR_2_Y, 1.3f), Vec3(-8.2f, FLOOR_2_Y, 1.3f))
        // Guest Room Wardrobe (Floor 2)
        addWardrobe("w_f2_guest", Vec3(9.35f, FLOOR_2_Y, 1.3f), -90f, Vec3(9.25f, FLOOR_2_Y, 1.3f), Vec3(8.2f, FLOOR_2_Y, 1.3f))
        // Living Room Wardrobe (Floor 1)
        addWardrobe("w_f1_living", Vec3(-9.35f, FLOOR_1_Y, 1.3f), 90f, Vec3(-9.25f, FLOOR_1_Y, 1.3f), Vec3(-8.2f, FLOOR_1_Y, 1.3f))
        // Lower Study Wardrobe (Floor 1)
        addWardrobe("w_f1_pantry", Vec3(9.35f, FLOOR_1_Y, -6.9f), -90f, Vec3(9.25f, FLOOR_1_Y, -6.9f), Vec3(8.2f, FLOOR_1_Y, -6.9f))
    }

    private fun addWardrobe(id: String, pos: Vec3, yaw: Float, hideSpot: Vec3, exitSpot: Vec3) {
        wardrobes.add(Wardrobe(id, pos, yaw, hideSpot, exitSpot))
        // Visual & physical back/side shell (slightly smaller than hideSpot so entering hides seamlessly)
        val cx = pos.x
        val y = pos.y
        val cz = pos.z
        addSolidBox(cx - 0.5f, y, cz - 0.65f, cx + 0.5f, y + 2.2f, cz + 0.65f, MaterialType.OAK_DOOR, 0.7f, addCollider = true)
    }

    private fun spawnEscapeItems() {
        // Crowbar always spawns on Floor 2 so player can find their first tool upstairs
        val f2Spots = listOf(
            Vec3(-9.1f, FLOOR_2_Y + 0.96f, -2.2f), // Bathroom vanity
            Vec3(4.6f, FLOOR_2_Y + 0.64f, 7.2f),   // Guest room chest
            Vec3(6.1f, FLOOR_2_Y + 0.84f, -4.6f)   // Upper study desk
        )
        val crowbarSpot = f2Spots[Random.nextInt(f2Spots.size)]
        items.add(CollectibleItem("item_crowbar", EscapeItemType.CROWBAR, crowbarSpot, rotationY = 25f))

        // Brass Key spawns in another room (Floor 2 or Floor 1 Dining/Living)
        val keySpots = listOf(
            Vec3(6.4f, FLOOR_1_Y + 0.86f, 4.3f),   // Dining Room banquet table
            Vec3(-7.7f, FLOOR_1_Y + 0.50f, 4.1f),  // Living Room coffee table
            Vec3(-4.0f, FLOOR_2_Y + 1.02f, 7.45f)  // Master Bedroom dresser
        ).filter { it.distanceTo(crowbarSpot) > 2.0f }
        val brassSpot = keySpots[Random.nextInt(keySpots.size)]
        items.add(CollectibleItem("item_brass_key", EscapeItemType.BRASS_KEY, brassSpot, rotationY = -35f))

        // Master Key always spawns on Floor 1 so you must brave downstairs!
        val f1Spots = listOf(
            Vec3(-6.4f, FLOOR_1_Y + 0.92f, -4.0f), // Kitchen island
            Vec3(6.8f, FLOOR_1_Y + 0.86f, -6.3f),  // Pantry / Lower Study desk
            Vec3(9.4f, FLOOR_1_Y + 1.07f, 4.1f)    // Dining sideboard
        ).filter { it.distanceTo(brassSpot) > 2.0f }
        val masterSpot = f1Spots[Random.nextInt(f1Spots.size)]
        items.add(CollectibleItem("item_master_key", EscapeItemType.MASTER_KEY, masterSpot, rotationY = 15f))
    }

    private fun buildNavGraph() {
        // Floor 1 nodes (0..12)
        addNode(0, Vec3(0f, FLOOR_1_Y, 5.5f), 1)       // Foyer South (near Exit)
        addNode(1, Vec3(0f, FLOOR_1_Y, 1.5f), 1)       // Foyer Stair Foot
        addNode(2, Vec3(-2.2f, FLOOR_1_Y, 4.2f), 1)    // Foyer West Door threshold
        addNode(3, Vec3(2.2f, FLOOR_1_Y, 4.2f), 1)     // Foyer East Door threshold
        addNode(4, Vec3(-2.2f, FLOOR_1_Y, -1.0f), 1)   // West Stair Corridor
        addNode(5, Vec3(2.2f, FLOOR_1_Y, -1.0f), 1)    // East Stair Corridor
        addNode(6, Vec3(-2.2f, FLOOR_1_Y, -4.2f), 1)   // Back Hall West Door threshold
        addNode(7, Vec3(2.2f, FLOOR_1_Y, -4.2f), 1)    // Back Hall East Door threshold
        addNode(8, Vec3(0f, FLOOR_1_Y, -6.6f), 1)      // Back Hall Center
        addNode(9, Vec3(-5.2f, FLOOR_1_Y, 4.2f), 1)    // Living Room Center
        addNode(10, Vec3(-6.5f, FLOOR_1_Y, -2.0f), 1)  // Kitchen South (clear of island!)
        addNode(11, Vec3(-4.5f, FLOOR_1_Y, -4.2f), 1)  // Kitchen East near door
        addNode(12, Vec3(4.5f, FLOOR_1_Y, 4.2f), 1)    // Dining Room West (clear of table)
        addNode(13, Vec3(6.5f, FLOOR_1_Y, -2.0f), 1)   // Pantry / Study South
        addNode(14, Vec3(4.5f, FLOOR_1_Y, -4.2f), 1)   // Pantry West near door

        // Staircase nodes (15..16)
        addNode(15, Vec3(0f, 1.8f, -2.5f), 1)          // Stair Midpoint

        // Floor 2 nodes (16..27)
        addNode(16, Vec3(0f, FLOOR_2_Y, -6.6f), 2)     // North Landing Top of Stairs
        addNode(17, Vec3(-2.2f, FLOOR_2_Y, -4.2f), 2)  // North Landing West Door
        addNode(18, Vec3(2.2f, FLOOR_2_Y, -4.2f), 2)   // North Landing East Door
        addNode(19, Vec3(-2.2f, FLOOR_2_Y, 0.5f), 2)   // West Gallery Walk
        addNode(20, Vec3(2.2f, FLOOR_2_Y, 0.5f), 2)    // East Gallery Walk
        addNode(21, Vec3(-2.2f, FLOOR_2_Y, 4.2f), 2)   // South Gallery West Door
        addNode(22, Vec3(2.2f, FLOOR_2_Y, 4.2f), 2)    // South Gallery East Door
        addNode(23, Vec3(0f, FLOOR_2_Y, 4.2f), 2)      // South Gallery Overlook Center
        addNode(24, Vec3(-5.5f, FLOOR_2_Y, 4.2f), 2)   // Master Bedroom Center
        addNode(25, Vec3(-6.5f, FLOOR_2_Y, -2.5f), 2)  // Bathroom Center
        addNode(26, Vec3(-4.5f, FLOOR_2_Y, -4.2f), 2)  // Bathroom East Door
        addNode(27, Vec3(5.5f, FLOOR_2_Y, 4.2f), 2)    // Guest Room Center
        addNode(28, Vec3(6.5f, FLOOR_2_Y, -2.0f), 2)   // Upper Study South
        addNode(29, Vec3(4.5f, FLOOR_2_Y, -4.2f), 2)   // Upper Study West Door

        // Connect Floor 1
        connect(0, 1); connect(0, 2); connect(0, 3)
        connect(1, 2); connect(1, 3); connect(1, 15)
        connect(2, 4); connect(2, 9)
        connect(3, 5); connect(3, 12)
        connect(4, 6); connect(5, 7)
        connect(6, 8); connect(7, 8)
        connect(6, 11); connect(7, 14)
        connect(9, 10); connect(10, 11)
        connect(12, 13); connect(13, 14)

        // Connect Staircase <-> Floor 2
        connect(15, 16)
        connect(16, 17); connect(16, 18)
        connect(17, 19); connect(17, 26)
        connect(18, 20); connect(18, 29)
        connect(19, 21); connect(20, 22)
        connect(21, 23); connect(22, 23)
        connect(21, 24); connect(22, 27)
        connect(24, 25); connect(25, 26)
        connect(27, 28); connect(28, 29)
    }

    private fun addNode(id: Int, pos: Vec3, floor: Int) {
        navNodes.add(NavNode(id, pos, floor))
    }

    private fun connect(a: Int, b: Int) {
        navNodes[a].neighbors.add(b)
        navNodes[b].neighbors.add(a)
    }

    fun getFloorHeightAt(x: Float, z: Float, currentFootY: Float): Float {
        // Check grand staircase zone: x in [-1.42, 1.42], z in [-5.55, 0.55]
        if (x in -1.42f..1.42f && z in -5.55f..0.55f) {
            val t = ((0.5f - z) / 6.0f).coerceIn(0f, 1f)
            val stairY = t * FLOOR_2_Y
            if (abs(currentFootY - stairY) < 1.35f) {
                return stairY
            }
        }
        return if (currentFootY >= 2.2f) FLOOR_2_Y else FLOOR_1_Y
    }

    fun resolveCollision(pos: Vec3, radius: Float, height: Float): Vec3 {
        var nx = pos.x
        var nz = pos.z
        val ny = getFloorHeightAt(nx, nz, pos.y)

        // Check static colliders + closed/partially-closed doors + exit door (until open)
        val allBoxes = ArrayList<AABB>(colliders.size + doors.size + 1)
        allBoxes.addAll(colliders)
        for (door in doors) {
            allBoxes.add(door.currentAABB())
        }
        if (exitProgress.openProgress < 0.7f) {
            allBoxes.add(AABB(-0.85f, 0f, 7.82f, 0.85f, 2.45f, 8.12f))
        }

        // Two relaxation passes prevent corner snagging
        for (pass in 0 until 2) {
            for (box in allBoxes) {
                if (!box.overlapsVertical(ny + 0.15f, ny + height)) continue
                val closestX = nx.coerceIn(box.minX, box.maxX)
                val closestZ = nz.coerceIn(box.minZ, box.maxZ)
                val dx = nx - closestX
                val dz = nz - closestZ
                val distSq = dx * dx + dz * dz
                if (distSq < radius * radius) {
                    val dist = kotlin.math.sqrt(distSq)
                    if (dist > 1e-5f) {
                        val push = radius - dist
                        nx += (dx / dist) * push
                        nz += (dz / dist) * push
                    } else {
                        // Inside box fallback: push along shallowest axis
                        val left = abs(nx - box.minX)
                        val right = abs(box.maxX - nx)
                        val back = abs(nz - box.minZ)
                        val front = abs(box.maxZ - nz)
                        val minPen = minOf(left, right, back, front)
                        when (minPen) {
                            left -> nx = box.minX - radius
                            right -> nx = box.maxX + radius
                            back -> nz = box.minZ - radius
                            else -> nz = box.maxZ + radius
                        }
                    }
                }
            }
        }

        val finalY = getFloorHeightAt(nx, nz, pos.y)
        return Vec3(nx, finalY, nz)
    }

    fun hasLineOfSight(from: Vec3, to: Vec3): Boolean {
        val diff = to - from
        val dist = diff.length()
        if (dist < 0.1f) return true
        val steps = (dist / 0.25f).toInt().coerceAtLeast(2)
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val p = from + diff * t
            for (box in colliders) {
                if (p.y > box.minY && p.y < box.maxY &&
                    p.x > box.minX && p.x < box.maxX &&
                    p.z > box.minZ && p.z < box.maxZ
                ) {
                    return false
                }
            }
            for (door in doors) {
                if (door.isMostlyOpen()) continue
                val b = door.currentAABB()
                if (p.y > b.minY && p.y < b.maxY &&
                    p.x > b.minX && p.x < b.maxX &&
                    p.z > b.minZ && p.z < b.maxZ
                ) {
                    return false
                }
            }
        }
        return true
    }
}
