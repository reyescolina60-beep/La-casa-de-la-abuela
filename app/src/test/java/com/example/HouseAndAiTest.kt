package com.example

import com.example.game.ai.GrandmaAI
import com.example.game.model.Difficulty
import com.example.game.model.EscapeItemType
import com.example.game.model.ExitDoorProgress
import com.example.game.model.Vec3
import com.example.game.world.HouseMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseAndAiTest {

    @Test
    fun playerAlwaysSpawnsOnSecondFloorAndGrandmaOnFirstFloor() {
        val house = HouseMap()
        val grandma = GrandmaAI(house)
        repeat(10) {
            house.resetForNewRun()
            grandma.reset()
            assertEquals("Player must spawn on Floor 2", HouseMap.FLOOR_2_Y, HouseMap.PLAYER_SPAWN.y, 0.01f)
            assertEquals("Grandma must spawn on Floor 1", HouseMap.FLOOR_1_Y, grandma.position.y, 0.01f)
            assertEquals(1, grandma.currentFloor())
        }
    }

    @Test
    fun staircaseTransitionsSmoothlyBetweenFirstAndSecondFloors() {
        val house = HouseMap()
        val topY = house.getFloorHeightAt(0f, -5.5f, HouseMap.FLOOR_2_Y)
        val midY = house.getFloorHeightAt(0f, -2.5f, 1.8f)
        val bottomY = house.getFloorHeightAt(0f, 0.5f, HouseMap.FLOOR_1_Y)

        assertEquals(HouseMap.FLOOR_2_Y, topY, 0.05f)
        assertEquals(1.8f, midY, 0.05f)
        assertEquals(HouseMap.FLOOR_1_Y, bottomY, 0.05f)
    }

    @Test
    fun allThreeEscapeItemsSpawnAcrossHouse() {
        val house = HouseMap()
        val types = house.items.map { it.type }.toSet()
        assertTrue(types.contains(EscapeItemType.CROWBAR))
        assertTrue(types.contains(EscapeItemType.BRASS_KEY))
        assertTrue(types.contains(EscapeItemType.MASTER_KEY))
        // Crowbar always spawns on Floor 2
        val crowbar = house.items.first { it.type == EscapeItemType.CROWBAR }
        assertTrue(crowbar.position.y >= HouseMap.FLOOR_2_Y)
    }

    @Test
    fun exitDoorRequiresAllThreeLocks() {
        var progress = ExitDoorProgress()
        assertFalse(progress.isFullyUnlocked)
        progress = progress.copy(planksRemoved = true)
        assertFalse(progress.isFullyUnlocked)
        progress = progress.copy(padlockUnlocked = true)
        assertFalse(progress.isFullyUnlocked)
        progress = progress.copy(deadboltUnlocked = true)
        assertTrue(progress.isFullyUnlocked)
    }

    @Test
    fun grandmaCanNavigateTowardSecondFloorNoise() {
        val house = HouseMap()
        val grandma = GrandmaAI(house)
        grandma.reset()
        val initialDist = grandma.position.distanceTo(Vec3(0f, HouseMap.FLOOR_1_Y, 1.5f))
        grandma.onNoiseHeard(Vec3(0f, HouseMap.FLOOR_2_Y, 4.0f), 25f, Difficulty.NIGHTMARE)
        repeat(60) {
            grandma.update(
                dt = 0.05f,
                playerPos = HouseMap.PLAYER_SPAWN,
                playerCrouching = false,
                playerFlashlightOn = true,
                playerHiding = true,
                difficulty = Difficulty.NIGHTMARE
            )
        }
        val afterDist = grandma.position.distanceTo(Vec3(0f, HouseMap.FLOOR_1_Y, 1.5f))
        assertTrue("Grandma should move closer to the staircase when investigating Floor 2", afterDist < initialDist)
    }
}
