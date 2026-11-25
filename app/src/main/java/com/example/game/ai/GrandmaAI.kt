package com.example.game.ai

import com.example.game.model.Difficulty
import com.example.game.model.GrandmaState
import com.example.game.model.Vec3
import com.example.game.world.HouseMap
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class GrandmaAI(private val house: HouseMap) {

    var position: Vec3 = HouseMap.GRANDMA_SPAWN
        private set
    var yawDeg: Float = 90f
        private set
    var state: GrandmaState = GrandmaState.PATROL
        private set
    var walkCycle: Float = 0f
        private set
    var headLookAngle: Float = 0f
        private set

    private var currentPath = mutableListOf<Int>()
    private var targetNodeId: Int = 10
    private var lastKnownPlayerPos: Vec3? = null
    private var searchTimer = 0f
    private var pathRecalcTimer = 0f
    private var stepSoundAccum = 0f
    private var idlePauseTimer = 0f

    // Guaranteed Floor 1 spawn on every reset
    fun reset() {
        position = HouseMap.GRANDMA_SPAWN
        yawDeg = 90f
        state = GrandmaState.PATROL
        walkCycle = 0f
        headLookAngle = 0f
        currentPath.clear()
        targetNodeId = 12 // Patrol toward Dining Room / Foyer on Floor 1
        lastKnownPlayerPos = null
        searchTimer = 0f
        pathRecalcTimer = 0f
        stepSoundAccum = 0f
        idlePauseTimer = 1.2f
    }

    fun onNoiseHeard(noisePos: Vec3, noiseRadius: Float, difficulty: Difficulty) {
        val dist = position.distanceTo(noisePos)
        if (dist <= noiseRadius * difficulty.hearingMultiplier) {
            if (state != GrandmaState.CHASE) {
                state = GrandmaState.INVESTIGATE
                lastKnownPlayerPos = noisePos
                pathRecalcTimer = 0f
            }
        }
    }

    data class UpdateResult(
        val caughtPlayer: Boolean,
        val steppedThisFrame: Boolean,
        val currentFloor: Int,
        val spottedPlayerNow: Boolean
    )

    fun update(
        dt: Float,
        playerPos: Vec3,
        playerCrouching: Boolean,
        playerFlashlightOn: Boolean,
        playerHiding: Boolean,
        difficulty: Difficulty
    ): UpdateResult {
        val eyePos = Vec3(position.x, position.y + 1.50f, position.z)
        val playerEye = Vec3(playerPos.x, playerPos.y + if (playerCrouching) 0.95f else 1.55f, playerPos.z)
        val distToPlayer = position.distanceTo(playerPos)

        // Check detection (stealth respects crouching, darkness, and hiding inside wardrobes!)
        val effectiveVision = difficulty.visionRange *
            (if (playerFlashlightOn) 1.15f else 0.72f) *
            (if (playerCrouching) 0.78f else 1.0f)

        var canSeePlayer = false
        if (!playerHiding && distToPlayer < effectiveVision) {
            val toPlayer = (playerEye - eyePos).normalized()
            val yawRad = yawDeg * (PI.toFloat() / 180f)
            val forward = Vec3(sin(yawRad), 0f, cos(yawRad))
            val horizDot = forward.x * toPlayer.x + forward.z * toPlayer.z
            // Wide awareness within 2.4m, directional 130-deg cone beyond
            if (distToPlayer < 2.6f || horizDot > 0.35f) {
                if (house.hasLineOfSight(eyePos, playerEye)) {
                    canSeePlayer = true
                }
            }
        }

        val prevState = state
        var spottedNow = false
        if (canSeePlayer) {
            state = GrandmaState.CHASE
            lastKnownPlayerPos = playerPos
            searchTimer = 4.5f
            if (prevState != GrandmaState.CHASE) {
                spottedNow = true
            }
        } else if (state == GrandmaState.CHASE) {
            searchTimer -= dt
            if (searchTimer <= 0f) {
                state = GrandmaState.SEARCH
                searchTimer = 4.0f
            }
        } else if (state == GrandmaState.SEARCH) {
            searchTimer -= dt
            if (searchTimer <= 0f) {
                state = GrandmaState.PATROL
                pickNewPatrolTarget(allowFloor2 = true)
            }
        }

        // Capture check (only if not hiding!)
        if (!playerHiding && distToPlayer < 1.05f && kotlin.math.abs(position.y - playerPos.y) < 1.2f) {
            if (house.hasLineOfSight(eyePos, playerEye)) {
                return UpdateResult(caughtPlayer = true, steppedThisFrame = false, currentFloor = currentFloor(), spottedPlayerNow = spottedNow)
            }
        }

        if (idlePauseTimer > 0f && state == GrandmaState.PATROL) {
            idlePauseTimer -= dt
            return UpdateResult(caughtPlayer = false, steppedThisFrame = false, currentFloor = currentFloor(), spottedPlayerNow = spottedNow)
        }

        pathRecalcTimer -= dt
        if (pathRecalcTimer <= 0f || currentPath.isEmpty()) {
            pathRecalcTimer = if (state == GrandmaState.CHASE) 0.35f else 0.9f
            when (state) {
                GrandmaState.CHASE, GrandmaState.INVESTIGATE, GrandmaState.SEARCH -> {
                    val goal = lastKnownPlayerPos ?: playerPos
                    val startId = nearestNode(position)
                    val endId = nearestNode(goal)
                    currentPath = findPathAStar(startId, endId)
                }
                GrandmaState.PATROL -> {
                    val startId = nearestNode(position)
                    if (startId == targetNodeId || currentPath.isEmpty()) {
                        pickNewPatrolTarget(allowFloor2 = true)
                    }
                    currentPath = findPathAStar(startId, targetNodeId)
                }
            }
        }

        // Determine movement target point
        var moveTarget: Vec3 = position
        if (state == GrandmaState.CHASE && canSeePlayer) {
            moveTarget = playerPos
        } else if (currentPath.isNotEmpty()) {
            val nextId = currentPath.first()
            val nodePos = house.navNodes[nextId].pos
            if (position.horizontalDistanceTo(nodePos) < 0.55f && kotlin.math.abs(position.y - nodePos.y) < 1.0f) {
                currentPath.removeAt(0)
                if (currentPath.isEmpty()) {
                    when (state) {
                        GrandmaState.INVESTIGATE -> {
                            state = GrandmaState.SEARCH
                            searchTimer = 3.2f
                        }
                        GrandmaState.PATROL -> {
                            idlePauseTimer = 0.6f + Random.nextFloat() * 1.1f
                            pickNewPatrolTarget(allowFloor2 = true)
                        }
                        else -> {}
                    }
                }
            }
            moveTarget = if (currentPath.isNotEmpty()) house.navNodes[currentPath.first()].pos else (lastKnownPlayerPos ?: position)
        }

        // Open any closed interior doors in Grandma's path!
        for (door in house.doors) {
            if (!door.isMostlyOpen() && position.distanceTo(door.centerPos()) < 1.75f) {
                door.targetOpen = 1f
            }
        }

        val dx = moveTarget.x - position.x
        val dz = moveTarget.z - position.z
        val horizLen = kotlin.math.sqrt(dx * dx + dz * dz)
        var stepped = false

        if (horizLen > 0.08f) {
            val speed = when (state) {
                GrandmaState.CHASE -> difficulty.grandmaChaseSpeed
                GrandmaState.INVESTIGATE -> difficulty.grandmaPatrolSpeed * 1.25f
                else -> difficulty.grandmaPatrolSpeed
            }
            val dirX = dx / horizLen
            val dirZ = dz / horizLen

            val targetYaw = atan2(dirX, dirZ) * (180f / PI.toFloat())
            yawDeg = lerpAngle(yawDeg, targetYaw, (dt * 7.5f).coerceIn(0f, 1f))

            val stepDist = speed * dt
            val nextPos = Vec3(position.x + dirX * stepDist, position.y, position.z + dirZ * stepDist)
            // Keep smooth stair vertical interpolation
            val resolved = house.resolveCollision(nextPos, radius = 0.34f, height = 1.72f)
            position = resolved

            val cycleRate = if (state == GrandmaState.CHASE) 8.5f else 5.0f
            walkCycle += dt * cycleRate
            stepSoundAccum += dt * cycleRate
            if (stepSoundAccum >= PI.toFloat()) {
                stepSoundAccum -= PI.toFloat()
                stepped = true
            }
        }

        headLookAngle = if (canSeePlayer) {
            val desired = atan2(playerPos.x - position.x, playerPos.z - position.z) * (180f / PI.toFloat())
            angleDiff(yawDeg, desired).coerceIn(-45f, 45f)
        } else {
            sin(walkCycle * 0.4f) * 14f
        }

        return UpdateResult(
            caughtPlayer = false,
            steppedThisFrame = stepped,
            currentFloor = currentFloor(),
            spottedPlayerNow = spottedNow
        )
    }

    fun currentFloor(): Int = if (position.y >= 2.2f) 2 else 1

    private fun pickNewPatrolTarget(allowFloor2: Boolean) {
        // Weight Floor 1 nodes heavily (70%) so Grandma frequently haunts the 1st floor,
        // with occasional Floor 2 patrols (30%) so upstairs is never completely safe!
        val f1Nodes = house.navNodes.filter { it.floor == 1 }
        val f2Nodes = house.navNodes.filter { it.floor == 2 }
        val pool = if (allowFloor2 && Random.nextFloat() < 0.30f && f2Nodes.isNotEmpty()) f2Nodes else f1Nodes
        targetNodeId = pool[Random.nextInt(pool.size)].id
    }

    private fun nearestNode(pos: Vec3): Int {
        var bestId = 0
        var bestScore = Float.MAX_VALUE
        for (node in house.navNodes) {
            val dy = kotlin.math.abs(node.pos.y - pos.y) * 2.5f
            val score = node.pos.horizontalDistanceTo(pos) + dy
            if (score < bestScore) {
                bestScore = score
                bestId = node.id
            }
        }
        return bestId
    }

    private fun findPathAStar(startId: Int, goalId: Int): MutableList<Int> {
        if (startId == goalId) return mutableListOf(goalId)
        val n = house.navNodes.size
        val gScore = FloatArray(n) { Float.MAX_VALUE }
        val fScore = FloatArray(n) { Float.MAX_VALUE }
        val cameFrom = IntArray(n) { -1 }
        val closed = BooleanArray(n)

        gScore[startId] = 0f
        fScore[startId] = house.navNodes[startId].pos.distanceTo(house.navNodes[goalId].pos)

        val open = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        open.add(startId to fScore[startId])

        while (open.isNotEmpty()) {
            val (curr, _) = open.poll() ?: break
            if (curr == goalId) {
                val path = mutableListOf<Int>()
                var c = goalId
                while (c != -1 && c != startId) {
                    path.add(c)
                    c = cameFrom[c]
                }
                path.reverse()
                return path
            }
            if (closed[curr]) continue
            closed[curr] = true

            val currNode = house.navNodes[curr]
            for (nbr in currNode.neighbors) {
                if (closed[nbr]) continue
                val tentativeG = gScore[curr] + currNode.pos.distanceTo(house.navNodes[nbr].pos)
                if (tentativeG < gScore[nbr]) {
                    cameFrom[nbr] = curr
                    gScore[nbr] = tentativeG
                    fScore[nbr] = tentativeG + house.navNodes[nbr].pos.distanceTo(house.navNodes[goalId].pos)
                    open.add(nbr to fScore[nbr])
                }
            }
        }
        return mutableListOf(goalId)
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        val diff = angleDiff(a, b)
        return a + diff * t
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
