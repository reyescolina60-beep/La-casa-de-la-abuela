package com.example.game.engine

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.game.ai.GrandmaAI
import com.example.game.audio.HorrorAudioEngine
import com.example.game.model.*
import com.example.game.world.HouseMap
import com.example.game.world.MeshBuffer
import com.example.game.world.WorldBuilder
import com.example.game.world.WorldMeshes
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class HorrorGLRenderer(
    private val context: Context,
    private val audio: HorrorAudioEngine,
    private val onHudUpdate: (HudState) -> Unit,
    private val onGameOver: (caught: Boolean, elapsedSeconds: Float, timesSpotted: Int) -> Unit
) : GLSurfaceView.Renderer {

    private val house = HouseMap()
    private val grandma = GrandmaAI(house)

    private lateinit var worldShader: WorldShaderProgram
    private lateinit var particleShader: ParticleShaderProgram
    private lateinit var textures: Map<MaterialType, MaterialTexturePair>
    private lateinit var meshes: WorldMeshes

    // Matrices
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Thread-safe input state
    @Volatile var moveStickX: Float = 0f
    @Volatile var moveStickY: Float = 0f
    @Volatile var isSprintingInput: Boolean = false
    @Volatile var lookSensitivity: Float = 1.0f
    @Volatile var isPaused: Boolean = false
    @Volatile var difficulty: Difficulty = Difficulty.NORMAL

    private var pendingLookDX = 0f
    private var pendingLookDY = 0f
    private val lookLock = Any()
    private val actionQueue = ConcurrentLinkedQueue<GameAction>()

    enum class GameAction {
        TOGGLE_FLASHLIGHT,
        TOGGLE_CROUCH,
        INTERACT,
        DISMISS_NOTE,
        RESET_RUN
    }

    // Player state
    private var playerPos = HouseMap.PLAYER_SPAWN
    private var playerYawDeg = HouseMap.PLAYER_SPAWN_YAW
    private var playerPitchDeg = -6f
    private var currentEyeOffset = 1.65f
    private var isFlashlightOn = true
    private var isCrouching = false
    private var isHiding = false
    private var hidingWardrobe: Wardrobe? = null
    private var stamina = 1.0f
    private var headBobPhase = 0f
    private var stepAccum = 0f
    private var heartbeatAccum = 0f
    private var elapsedSeconds = 0f
    private var timesSpotted = 0
    private var runEnded = false
    private var escapingWalkTimer = 0f
    private var screenShake = 0f

    private val collectedItems = mutableListOf<EscapeItemType>()
    private var noteOverlayText: String? = null
    private var statusBanner: String? = null
    private var statusBannerTimer = 0f

    private var lastFrameNanos = System.nanoTime()
    private var hudEmitAccum = 0f
    private var glTime = 0f

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun addLookDelta(dx: Float, dy: Float) {
        synchronized(lookLock) {
            pendingLookDX += dx
            pendingLookDY += dy
        }
    }

    fun enqueueAction(action: GameAction) {
        actionQueue.add(action)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.024f, 0.026f, 0.036f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        worldShader = WorldShaderProgram()
        particleShader = ParticleShaderProgram()
        textures = ProceduralTextures.generateAllMaterials()
        meshes = WorldBuilder.buildWorldMeshes(house)

        resetSimulation()
        lastFrameNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / maxOf(height, 1).toFloat()
        Matrix.perspectiveM(projMatrix, 0, 68f, aspect, 0.08f, 45f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val rawDt = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        val dt = rawDt.coerceIn(0.001f, 0.05f)

        processQueuedActions()

        if (!isPaused && !runEnded) {
            glTime += dt
            updateSimulation(dt)
        } else if (escapingWalkTimer > 0f) {
            glTime += dt
            updateEscapeAnimation(dt)
        }

        renderScene()
    }

    private fun processQueuedActions() {
        while (true) {
            val action = actionQueue.poll() ?: break
            when (action) {
                GameAction.TOGGLE_FLASHLIGHT -> {
                    isFlashlightOn = !isFlashlightOn
                    audio.playFlashClick()
                    emitHudNow()
                }
                GameAction.TOGGLE_CROUCH -> {
                    if (!isHiding) {
                        isCrouching = !isCrouching
                        emitHudNow()
                    }
                }
                GameAction.DISMISS_NOTE -> {
                    noteOverlayText = null
                    emitHudNow()
                }
                GameAction.INTERACT -> handleInteract()
                GameAction.RESET_RUN -> resetSimulation()
            }
        }
    }

    private fun resetSimulation() {
        house.resetForNewRun()
        grandma.reset() // Always spawns on Floor 1!
        playerPos = HouseMap.PLAYER_SPAWN // Always spawns on Floor 2!
        playerYawDeg = HouseMap.PLAYER_SPAWN_YAW
        playerPitchDeg = -6f
        currentEyeOffset = 1.65f
        isFlashlightOn = true
        isCrouching = false
        isHiding = false
        hidingWardrobe = null
        stamina = 1.0f
        headBobPhase = 0f
        stepAccum = 0f
        heartbeatAccum = 0f
        elapsedSeconds = 0f
        timesSpotted = 0
        runEnded = false
        escapingWalkTimer = 0f
        screenShake = 0f
        collectedItems.clear()
        noteOverlayText = null
        statusBanner = "Despertaste en el 2.º piso. Encuentra las herramientas y escapa por la puerta del 1.º piso."
        statusBannerTimer = 5.5f
        emitHudNow()
    }

    private fun updateEscapeAnimation(dt: Float) {
        escapingWalkTimer -= dt
        house.exitProgress.openProgress = (house.exitProgress.openProgress + dt * 1.4f).coerceAtMost(1f)
        playerPos = Vec3(playerPos.x * 0.96f, playerPos.y, playerPos.z + dt * 2.1f)
        playerPitchDeg *= 0.95f
        if (escapingWalkTimer <= 0f) {
            onGameOver(false, elapsedSeconds, timesSpotted)
        }
    }

    private fun updateSimulation(dt: Float) {
        if (noteOverlayText != null) return // Freeze action softly while reading note

        elapsedSeconds += dt
        if (statusBannerTimer > 0f) {
            statusBannerTimer -= dt
            if (statusBannerTimer <= 0f) statusBanner = null
        }

        // Apply look deltas
        val dx: Float
        val dy: Float
        synchronized(lookLock) {
            dx = pendingLookDX
            dy = pendingLookDY
            pendingLookDX = 0f
            pendingLookDY = 0f
        }
        val sens = 0.17f * lookSensitivity
        playerYawDeg = (playerYawDeg + dx * sens) % 360f
        playerPitchDeg = (playerPitchDeg - dy * sens).coerceIn(-75f, 75f)

        // Update door smooth swing
        for (door in house.doors) {
            val diff = door.targetOpen - door.openAmount
            if (abs(diff) > 0.005f) {
                door.openAmount += diff * (dt * 6.5f).coerceIn(0f, 1f)
            }
        }
        // Update wardrobe door animation
        for (w in house.wardrobes) {
            val target = if (isHiding && hidingWardrobe == w) 0.18f else 0f
            w.doorOpenAmount += (target - w.doorOpenAmount) * (dt * 8f).coerceIn(0f, 1f)
        }

        // Player movement
        val wantMove = abs(moveStickX) > 0.05f || abs(moveStickY) > 0.05f
        val canSprint = isSprintingInput && !isCrouching && !isHiding && stamina > 0.05f && wantMove
        if (canSprint) {
            stamina = (stamina - dt * 0.24f).coerceAtLeast(0f)
        } else {
            stamina = (stamina + dt * 0.16f).coerceAtMost(1f)
        }

        if (!isHiding && wantMove) {
            val speed = when {
                isCrouching -> 1.85f
                canSprint -> 4.35f
                else -> 2.85f
            }
            val yawRad = playerYawDeg * (PI.toFloat() / 180f)
            val fwdX = sin(yawRad)
            val fwdZ = -cos(yawRad)
            val rightX = cos(yawRad)
            val rightZ = sin(yawRad)

            // moveStickY > 0 is forward (up on joystick), moveStickX > 0 is right
            val wishX = fwdX * moveStickY + rightX * moveStickX
            val wishZ = fwdZ * moveStickY + rightZ * moveStickX
            val wishLen = sqrt(wishX * wishX + wishZ * wishZ).coerceAtLeast(0.001f)
            val normMag = wishLen.coerceAtMost(1f)
            val vx = (wishX / wishLen) * speed * normMag
            val vz = (wishZ / wishLen) * speed * normMag

            val candidate = Vec3(playerPos.x + vx * dt, playerPos.y, playerPos.z + vz * dt)
            playerPos = house.resolveCollision(candidate, radius = 0.36f, height = if (isCrouching) 1.15f else 1.78f)

            val bobRate = if (canSprint) 11.5f else if (isCrouching) 5.5f else 7.8f
            headBobPhase += dt * bobRate * normMag
            stepAccum += dt * bobRate * normMag
            if (stepAccum >= PI.toFloat()) {
                stepAccum -= PI.toFloat()
                audio.playPlayerStep(canSprint, isCrouching)
                // Footstep noise attracts Grandma!
                val noiseRadius = if (canSprint) 12.5f else if (isCrouching) 2.2f else 5.8f
                grandma.onNoiseHeard(playerPos, noiseRadius, difficulty)
            }
        } else {
            headBobPhase += dt * 1.4f // gentle breathing sway
        }

        // Smooth eye height for crouch / hide
        val targetEye = if (isCrouching) 1.00f else 1.65f
        currentEyeOffset += (targetEye - currentEyeOffset) * (dt * 9f).coerceIn(0f, 1f)

        // Update Grandma AI
        val aiRes = grandma.update(
            dt = dt,
            playerPos = playerPos,
            playerCrouching = isCrouching,
            playerFlashlightOn = isFlashlightOn,
            playerHiding = isHiding,
            difficulty = difficulty
        )

        if (aiRes.spottedPlayerNow) {
            timesSpotted++
            vibratePulse(60L)
            statusBanner = "¡La Abuela te ha visto!"
            statusBannerTimer = 2.6f
        }

        if (aiRes.steppedThisFrame) {
            val toGrandma = grandma.position - playerPos
            val dist = toGrandma.length()
            val yawRad = playerYawDeg * (PI.toFloat() / 180f)
            val rightVec = Vec3(cos(yawRad), 0f, sin(yawRad))
            val pan = if (dist > 0.1f) (toGrandma.x * rightVec.x + toGrandma.z * rightVec.z) / dist else 0f
            val playerFloor = if (playerPos.y >= 2.2f) 2 else 1
            audio.playGrandmaStep(dist, pan, playerFloor == aiRes.currentFloor)
        }

        if (aiRes.caughtPlayer) {
            runEnded = true
            screenShake = 1.0f
            audio.playScare()
            vibratePulse(280L)
            // Snap camera toward Grandma's face for dramatic scare
            val dxG = grandma.position.x - playerPos.x
            val dzG = grandma.position.z - playerPos.z
            playerYawDeg = kotlin.math.atan2(dxG, -dzG) * (180f / PI.toFloat())
            playerPitchDeg = 4f
            emitHudNow()
            onGameOver(true, elapsedSeconds, timesSpotted)
            return
        }

        // Heartbeat & danger proximity feedback
        val gDist = playerPos.distanceTo(grandma.position)
        val danger = ((9.5f - gDist) / 7.5f).coerceIn(0f, 1f)
        if (danger > 0.25f) {
            val beatInterval = (1.05f - danger * 0.65f).coerceAtLeast(0.36f)
            heartbeatAccum += dt
            if (heartbeatAccum >= beatInterval) {
                heartbeatAccum = 0f
                audio.playHeartbeat(danger)
                if (danger > 0.72f) vibratePulse(28L)
            }
        }

        if (screenShake > 0f) {
            screenShake = (screenShake - dt * 2.5f).coerceAtLeast(0f)
        }

        hudEmitAccum += dt
        if (hudEmitAccum >= 0.08f) {
            hudEmitAccum = 0f
            emitHudNow()
        }
    }

    private sealed class InteractTarget {
        data class ItemTarget(val item: CollectibleItem) : InteractTarget()
        data class DoorTarget(val door: DoorState) : InteractTarget()
        data class WardrobeTarget(val wardrobe: Wardrobe) : InteractTarget()
        object NoteTarget : InteractTarget()
        object ExitDoorTarget : InteractTarget()
    }

    private fun findLookTarget(): Pair<InteractTarget, String>? {
        if (isHiding) {
            val w = hidingWardrobe ?: return null
            return InteractTarget.WardrobeTarget(w) to "Salir del armario"
        }

        val eye = getCameraEyePos()
        val look = getLookDir()
        var bestTarget: InteractTarget? = null
        var bestPrompt = ""
        var bestScore = Float.MAX_VALUE

        fun testPoint(pos: Vec3, maxDist: Float, minDot: Float): Float? {
            val diff = pos - eye
            val dist = diff.length()
            if (dist > maxDist) return null
            val dir = diff.normalized()
            val dot = dir.x * look.x + dir.y * look.y + dir.z * look.z
            if (dot < minDot) return null
            return dist - dot * 0.8f
        }

        // 1. Items
        for (item in house.items) {
            if (item.collected) continue
            val score = testPoint(item.position, 2.35f, 0.78f) ?: continue
            if (score < bestScore) {
                bestScore = score
                bestTarget = InteractTarget.ItemTarget(item)
                bestPrompt = "Recoger ${item.type.displayName}"
            }
        }

        // 2. Starting Note
        if (!house.noteRead) {
            val score = testPoint(HouseMap.NOTE_POS, 2.3f, 0.75f)
            if (score != null && score < bestScore) {
                bestScore = score
                bestTarget = InteractTarget.NoteTarget
                bestPrompt = "Leer nota de la mesita"
            }
        }

        // 3. Exit Door (1st Floor South Foyer)
        val exitScore = testPoint(HouseMap.EXIT_DOOR_POS, 2.65f, 0.72f)
        if (exitScore != null && exitScore < bestScore) {
            bestScore = exitScore
            bestTarget = InteractTarget.ExitDoorTarget
            val ep = house.exitProgress
            bestPrompt = when {
                !ep.planksRemoved -> if (collectedItems.contains(EscapeItemType.CROWBAR))
                    "Arrancar tablones [Palanca]" else "Bloqueada por tablones (Falta Palanca)"
                !ep.padlockUnlocked -> if (collectedItems.contains(EscapeItemType.BRASS_KEY))
                    "Abrir candado [Llave de Bronce]" else "Cerrada con candado (Falta Llave de Bronce)"
                !ep.deadboltUnlocked -> if (collectedItems.contains(EscapeItemType.MASTER_KEY))
                    "Destrabar cerrojo [Llave Maestra]" else "Cerrojo echado (Falta Llave Maestra)"
                else -> "Abrir puerta y ESCAPAR"
            }
        }

        // 4. Doors
        for (door in house.doors) {
            val score = testPoint(door.centerPos(), 2.35f, 0.72f) ?: continue
            if (score < bestScore) {
                bestScore = score
                bestTarget = InteractTarget.DoorTarget(door)
                bestPrompt = if (door.targetOpen > 0.5f) "Cerrar puerta" else "Abrir puerta"
            }
        }

        // 5. Wardrobes
        for (w in house.wardrobes) {
            val center = Vec3(w.position.x, w.position.y + 1.1f, w.position.z)
            val score = testPoint(center, 2.4f, 0.72f) ?: continue
            if (score < bestScore) {
                bestScore = score
                bestTarget = InteractTarget.WardrobeTarget(w)
                bestPrompt = "Esconderse en el armario"
            }
        }

        return bestTarget?.let { it to bestPrompt }
    }

    private fun handleInteract() {
        if (runEnded || isPaused) return
        val (target, _) = findLookTarget() ?: return

        when (target) {
            is InteractTarget.ItemTarget -> {
                target.item.collected = true
                collectedItems.add(target.item.type)
                audio.playPickup()
                vibratePulse(35L)
                statusBanner = "Encontraste: ${target.item.type.displayName}"
                statusBannerTimer = 3.2f
            }
            is InteractTarget.NoteTarget -> {
                house.noteRead = true
                noteOverlayText = "“Despertaste en el segundo piso. La abuela merodea abajo en la primera planta y escucha tus pasos si corres.\n\nPara abrir la puerta principal del 1.º piso necesitas:\n1. La Palanca de Hierro (tablones)\n2. La Llave de Bronce (candado)\n3. La Llave Maestra (cerrojo)\n\nEscóndete en los armarios si te persigue.”"
            }
            is InteractTarget.DoorTarget -> {
                target.door.targetOpen = if (target.door.targetOpen > 0.5f) 0f else 1f
                audio.playDoorCreak()
                grandma.onNoiseHeard(target.door.centerPos(), 7.5f, difficulty)
            }
            is InteractTarget.WardrobeTarget -> {
                if (!isHiding) {
                    isHiding = true
                    isCrouching = false
                    hidingWardrobe = target.wardrobe
                    playerPos = target.wardrobe.hideSpot
                    playerYawDeg = target.wardrobe.facingYawDeg
                    playerPitchDeg = 0f
                    audio.playDoorCreak()
                    statusBanner = "Escondido en el armario (apaga la linterna para pasar desapercibido)"
                    statusBannerTimer = 3.0f
                } else {
                    isHiding = false
                    playerPos = target.wardrobe.exitSpot
                    hidingWardrobe = null
                    audio.playDoorCreak()
                }
            }
            InteractTarget.ExitDoorTarget -> {
                val ep = house.exitProgress
                when {
                    !ep.planksRemoved -> {
                        if (collectedItems.contains(EscapeItemType.CROWBAR)) {
                            house.exitProgress = ep.copy(planksRemoved = true)
                            audio.playWoodSnap()
                            vibratePulse(70L)
                            grandma.onNoiseHeard(HouseMap.EXIT_DOOR_POS, 14f, difficulty)
                            statusBanner = "¡Tablones arrancados! La abuela oyó el ruido..."
                            statusBannerTimer = 3.2f
                        } else {
                            audio.playLockOpen()
                            statusBanner = "Necesitas la Palanca de Hierro para quitar los tablones."
                            statusBannerTimer = 2.8f
                        }
                    }
                    !ep.padlockUnlocked -> {
                        if (collectedItems.contains(EscapeItemType.BRASS_KEY)) {
                            house.exitProgress = ep.copy(padlockUnlocked = true)
                            audio.playLockOpen()
                            vibratePulse(50L)
                            statusBanner = "¡Candado abierto!"
                            statusBannerTimer = 2.6f
                        } else {
                            audio.playLockOpen()
                            statusBanner = "Falta la Llave de Bronce para el candado."
                            statusBannerTimer = 2.6f
                        }
                    }
                    !ep.deadboltUnlocked -> {
                        if (collectedItems.contains(EscapeItemType.MASTER_KEY)) {
                            house.exitProgress = ep.copy(deadboltUnlocked = true)
                            audio.playLockOpen()
                            vibratePulse(50L)
                            statusBanner = "¡Cerrojo liberado! Toca de nuevo para abrir la puerta."
                            statusBannerTimer = 3.0f
                        } else {
                            audio.playLockOpen()
                            statusBanner = "Falta la Llave Maestra para el cerrojo final."
                            statusBannerTimer = 2.6f
                        }
                    }
                    else -> {
                        // ESCAPE VICTORY!
                        runEnded = true
                        escapingWalkTimer = 1.45f
                        audio.playEscape()
                        vibratePulse(120L)
                        statusBanner = "¡La puerta está abierta! ¡ESCAPASTE!"
                        statusBannerTimer = 3.0f
                    }
                }
            }
        }
        emitHudNow()
    }

    private fun emitHudNow() {
        val target = findLookTarget()
        val gDist = playerPos.distanceTo(grandma.position)
        val danger = if (isHiding && grandma.state != GrandmaState.CHASE) {
            ((6.0f - gDist) / 5.0f).coerceIn(0f, 0.45f)
        } else {
            ((9.5f - gDist) / 7.5f).coerceIn(0f, 1f)
        }
        onHudUpdate(
            HudState(
                elapsedSeconds = elapsedSeconds,
                playerFloor = if (playerPos.y >= 2.2f) 2 else 1,
                grandmaFloor = grandma.currentFloor(),
                isFlashlightOn = isFlashlightOn,
                isCrouching = isCrouching,
                isSprinting = isSprintingInput && !isCrouching && stamina > 0.05f,
                isHiding = isHiding,
                stamina = stamina,
                dangerProximity = danger,
                grandmaChasing = grandma.state == GrandmaState.CHASE,
                interactionPrompt = target?.second,
                canInteract = target != null,
                collectedItems = collectedItems.toList(),
                exitProgress = house.exitProgress.copy(),
                statusBanner = statusBanner,
                noteOverlayText = noteOverlayText,
                timesSpotted = timesSpotted
            )
        )
    }

    private fun getCameraEyePos(): Vec3 {
        val bobY = if (isHiding) 0f else sin(headBobPhase * 2f) * 0.032f
        val shakeX = if (screenShake > 0f) (Random.nextFloat() - 0.5f) * 0.06f * screenShake else 0f
        val shakeY = if (screenShake > 0f) (Random.nextFloat() - 0.5f) * 0.06f * screenShake else 0f
        return Vec3(
            playerPos.x + shakeX,
            playerPos.y + currentEyeOffset + bobY + shakeY,
            playerPos.z
        )
    }

    private fun getLookDir(): Vec3 {
        val yawRad = playerYawDeg * (PI.toFloat() / 180f)
        val pitchRad = playerPitchDeg * (PI.toFloat() / 180f)
        val cp = cos(pitchRad)
        return Vec3(
            sin(yawRad) * cp,
            sin(pitchRad),
            -cos(yawRad) * cp
        ).normalized()
    }

    private fun renderScene() {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val eye = getCameraEyePos()
        val look = getLookDir()
        val target = eye + look
        Matrix.setLookAtM(viewMatrix, 0, eye.x, eye.y, eye.z, target.x, target.y, target.z, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(worldShader.programId)
        GLES30.glUniform3f(worldShader.uCameraPosLoc, eye.x, eye.y, eye.z)

        // Flashlight position slightly below & right of camera eye for realistic hand-held shadows
        val yawRad = playerYawDeg * (PI.toFloat() / 180f)
        val right = Vec3(cos(yawRad), 0f, sin(yawRad))
        val flashPos = eye + right * 0.14f + Vec3(0f, -0.11f, 0f) + look * 0.12f
        val gDist = playerPos.distanceTo(grandma.position)
        var flashPower = if (isFlashlightOn) 1.0f else 0.0f
        if (isFlashlightOn && gDist < 6.0f) {
            // Subtle supernatural horror flicker when Grandma is close!
            val flick = sin(glTime * 34f) * sin(glTime * 19.3f)
            if (flick > 0.65f) flashPower *= 0.25f
        }
        GLES30.glUniform3f(worldShader.uFlashPosLoc, flashPos.x, flashPos.y, flashPos.z)
        GLES30.glUniform3f(worldShader.uFlashDirLoc, look.x, look.y, look.z)
        GLES30.glUniform1f(worldShader.uFlashPowerLoc, flashPower)

        // Fog
        GLES30.glUniform3f(worldShader.uFogColorLoc, 0.028f, 0.032f, 0.044f)
        GLES30.glUniform1f(worldShader.uFogDensityLoc, 0.068f)

        // Upload 8 closest point lights
        val sortedLights = house.pointLights
            .sortedBy { it.position.distanceTo(eye) }
            .take(8)
        val count = sortedLights.size
        val posArr = FloatArray(8 * 3)
        val colArr = FloatArray(8 * 3)
        val radArr = FloatArray(8)
        for (i in 0 until count) {
            val l = sortedLights[i]
            val flick = if (l.flickerSpeed > 0f) {
                1f + sin(glTime * l.flickerSpeed + i * 1.7f) * l.flickerAmount * 0.6f +
                    sin(glTime * l.flickerSpeed * 2.3f) * l.flickerAmount * 0.4f
            } else 1f
            val intens = l.baseIntensity * flick
            posArr[i * 3] = l.position.x
            posArr[i * 3 + 1] = l.position.y
            posArr[i * 3 + 2] = l.position.z
            colArr[i * 3] = l.color.x * intens
            colArr[i * 3 + 1] = l.color.y * intens
            colArr[i * 3 + 2] = l.color.z * intens
            radArr[i] = l.radius
        }
        GLES30.glUniform1i(worldShader.uNumLightsLoc, count)
        GLES30.glUniform3fv(worldShader.uLightPosLoc, 8, posArr, 0)
        GLES30.glUniform3fv(worldShader.uLightColorLoc, 8, colArr, 0)
        GLES30.glUniform1fv(worldShader.uLightRadiusLoc, 8, radArr, 0)

        GLES30.glUniform1i(worldShader.uDiffuseMapLoc, 0)
        GLES30.glUniform1i(worldShader.uNormalMapLoc, 1)

        // 1. Static Manor Geometry
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(worldShader.uModelLoc, 1, false, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(worldShader.uMVPLoc, 1, false, mvpMatrix, 0)

        for (batch in meshes.staticBatches) {
            bindMaterial(batch.material)
            GLES30.glUniform3f(worldShader.uTintLoc, batch.tint.x, batch.tint.y, batch.tint.z)
            GLES30.glUniform3f(worldShader.uEmissiveLoc, batch.emissive.x, batch.emissive.y, batch.emissive.z)
            drawMesh(batch.mesh)
        }

        // 2. Interior Swinging Doors
        bindMaterial(MaterialType.OAK_DOOR)
        GLES30.glUniform3f(worldShader.uTintLoc, 1f, 1f, 1f)
        GLES30.glUniform3f(worldShader.uEmissiveLoc, 0f, 0f, 0f)
        for (door in house.doors) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, door.hingePos.x, door.hingePos.y, door.hingePos.z)
            val angleDeg = door.openAmount * 88f * door.swingSign
            Matrix.rotateM(modelMatrix, 0, -angleDeg, 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, 1f, door.height, door.width)
            drawMeshWithModel(meshes.doorPanel)
        }

        // 3. Wardrobe Front Doors
        for (w in house.wardrobes) {
            Matrix.setIdentityM(modelMatrix, 0)
            val frontX = if (w.facingYawDeg > 0f) w.position.x + 0.52f else w.position.x - 0.52f
            Matrix.translateM(modelMatrix, 0, frontX, w.position.y + 1.1f, w.position.z)
            Matrix.scaleM(modelMatrix, 0, 0.06f, 2.15f, 1.24f)
            bindMaterial(MaterialType.OAK_DOOR)
            GLES30.glUniform3f(worldShader.uTintLoc, 0.85f, 0.78f, 0.72f)
            GLES30.glUniform3f(worldShader.uEmissiveLoc, 0f, 0f, 0f)
            drawMeshWithModel(meshes.unitBox)
        }

        // 4. Main Exit Door + Visual Barricades (Planks, Padlock, Deadbolt)
        renderExitDoor()

        // 5. Survival Note on Nightstand
        if (!house.noteRead) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, HouseMap.NOTE_POS.x, HouseMap.NOTE_POS.y, HouseMap.NOTE_POS.z)
            Matrix.rotateM(modelMatrix, 0, 18f, 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, 0.28f, 0.015f, 0.36f)
            bindMaterial(MaterialType.PLASTER_CEILING)
            GLES30.glUniform3f(worldShader.uTintLoc, 1.6f, 1.48f, 1.22f)
            GLES30.glUniform3f(worldShader.uEmissiveLoc, 0.10f, 0.09f, 0.06f)
            drawMeshWithModel(meshes.unitBox)
        }

        // 6. Collectible Escape Items
        renderCollectibleItems()

        // 7. Articulated 3D Grandma
        renderGrandma()

        // 8. Floating Dust Particles
        renderDustMotes()
    }

    private fun renderExitDoor() {
        val ep = house.exitProgress
        val openAngle = ep.openProgress * 85f

        // Heavy double-width oak exit door hinged at x = -0.84, z = 7.95
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, -0.84f, 0f, 7.95f)
        Matrix.rotateM(modelMatrix, 0, -openAngle, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, -90f, 0f, 1f, 0f) // Orient along +X
        Matrix.scaleM(modelMatrix, 0, 1.4f, 2.42f, 1.68f)
        bindMaterial(MaterialType.OAK_DOOR)
        GLES30.glUniform3f(worldShader.uTintLoc, 1.15f, 0.92f, 0.72f)
        GLES30.glUniform3f(worldShader.uEmissiveLoc, 0f, 0f, 0f)
        drawMeshWithModel(meshes.doorPanel)

        // Nailed Wooden Planks across door (until Crowbar used)
        if (!ep.planksRemoved) {
            bindMaterial(MaterialType.WOOD_FLOOR)
            GLES30.glUniform3f(worldShader.uTintLoc, 1.1f, 0.95f, 0.78f)
            for ((py, tilt) in listOf(0.85f to 8f, 1.55f to -6f)) {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, 0f, py, 7.82f)
                Matrix.rotateM(modelMatrix, 0, tilt, 0f, 0f, 1f)
                Matrix.scaleM(modelMatrix, 0, 1.72f, 0.16f, 0.06f)
                drawMeshWithModel(meshes.unitBox)
            }
        }

        // Brass Padlock (until Brass Key used)
        if (!ep.padlockUnlocked) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0.42f, 1.12f, 7.78f)
            Matrix.scaleM(modelMatrix, 0, 0.16f, 0.22f, 0.08f)
            bindMaterial(MaterialType.RUSTED_METAL)
            GLES30.glUniform3f(worldShader.uTintLoc, 1.5f, 1.25f, 0.55f)
            GLES30.glUniform3f(worldShader.uEmissiveLoc, 0.08f, 0.06f, 0.01f)
            drawMeshWithModel(meshes.unitBox)
        }

        // Heavy Iron Deadbolt (until Master Key used)
        if (!ep.deadboltUnlocked) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0.52f, 1.38f, 7.79f)
            Matrix.scaleM(modelMatrix, 0, 0.34f, 0.09f, 0.07f)
            bindMaterial(MaterialType.RUSTED_METAL)
            GLES30.glUniform3f(worldShader.uTintLoc, 0.9f, 0.92f, 1.0f)
            GLES30.glUniform3f(worldShader.uEmissiveLoc, 0.05f, 0.05f, 0.06f)
            drawMeshWithModel(meshes.unitBox)
        }
    }

    private fun renderCollectibleItems() {
        val glint = 0.08f + 0.06f * sin(glTime * 3.5f)
        for (item in house.items) {
            if (item.collected) continue
            val p = item.position
            bindMaterial(MaterialType.RUSTED_METAL)

            when (item.type) {
                EscapeItemType.CROWBAR -> {
                    GLES30.glUniform3f(worldShader.uTintLoc, 1.15f, 0.48f, 0.42f)
                    GLES30.glUniform3f(worldShader.uEmissiveLoc, glint, glint * 0.4f, glint * 0.3f)
                    // Main shaft
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, p.x, p.y + 0.04f, p.z)
                    Matrix.rotateM(modelMatrix, 0, item.rotationY + glTime * 18f, 0f, 1f, 0f)
                    Matrix.scaleM(modelMatrix, 0, 0.56f, 0.045f, 0.045f)
                    drawMeshWithModel(meshes.unitBox)
                    // Curved claw tip
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, p.x, p.y + 0.04f, p.z)
                    Matrix.rotateM(modelMatrix, 0, item.rotationY + glTime * 18f, 0f, 1f, 0f)
                    Matrix.translateM(modelMatrix, 0, 0.28f, 0.03f, 0f)
                    Matrix.rotateM(modelMatrix, 0, 35f, 0f, 0f, 1f)
                    Matrix.scaleM(modelMatrix, 0, 0.14f, 0.04f, 0.05f)
                    drawMeshWithModel(meshes.unitBox)
                }
                EscapeItemType.BRASS_KEY, EscapeItemType.MASTER_KEY -> {
                    val isMaster = item.type == EscapeItemType.MASTER_KEY
                    if (isMaster) {
                        GLES30.glUniform3f(worldShader.uTintLoc, 1.45f, 1.45f, 1.6f)
                        GLES30.glUniform3f(worldShader.uEmissiveLoc, glint * 0.9f, glint * 0.95f, glint * 1.2f)
                    } else {
                        GLES30.glUniform3f(worldShader.uTintLoc, 1.65f, 1.28f, 0.48f)
                        GLES30.glUniform3f(worldShader.uEmissiveLoc, glint * 1.2f, glint * 0.9f, glint * 0.25f)
                    }
                    val rot = item.rotationY + glTime * 28f
                    // Key shaft
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, p.x, p.y + 0.04f, p.z)
                    Matrix.rotateM(modelMatrix, 0, rot, 0f, 1f, 0f)
                    Matrix.scaleM(modelMatrix, 0, 0.28f, 0.025f, 0.03f)
                    drawMeshWithModel(meshes.unitBox)
                    // Bow handle
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, p.x, p.y + 0.04f, p.z)
                    Matrix.rotateM(modelMatrix, 0, rot, 0f, 1f, 0f)
                    Matrix.translateM(modelMatrix, 0, -0.14f, 0f, 0f)
                    Matrix.scaleM(modelMatrix, 0, 0.10f, 0.025f, 0.10f)
                    drawMeshWithModel(meshes.unitBox)
                    // Key teeth
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, p.x, p.y + 0.04f, p.z)
                    Matrix.rotateM(modelMatrix, 0, rot, 0f, 1f, 0f)
                    Matrix.translateM(modelMatrix, 0, 0.11f, 0f, 0.035f)
                    Matrix.scaleM(modelMatrix, 0, 0.06f, 0.025f, 0.06f)
                    drawMeshWithModel(meshes.unitBox)
                }
            }
        }
    }

    private fun renderGrandma() {
        val gPos = grandma.position
        val gYaw = grandma.yawDeg
        val wc = grandma.walkCycle
        val gm = meshes.grandma
        val swing = sin(wc) * 28f
        val limpBob = abs(sin(wc)) * 0.055f
        val hunch = if (grandma.state == GrandmaState.CHASE) 22f else 14f

        // Root transform at Grandma's feet
        fun baseRoot(): FloatArray {
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, gPos.x, gPos.y + limpBob, gPos.z)
            Matrix.rotateM(m, 0, gYaw, 0f, 1f, 0f)
            return m
        }

        // Legs
        bindMaterial(MaterialType.GRANDMA_SKIN)
        GLES30.glUniform3f(worldShader.uTintLoc, 0.65f, 0.62f, 0.60f)
        GLES30.glUniform3f(worldShader.uEmissiveLoc, 0f, 0f, 0f)
        for ((side, sAngle) in listOf(-0.10f to swing, 0.10f to -swing)) {
            val m = baseRoot()
            Matrix.translateM(m, 0, side, 0.72f, 0f)
            Matrix.rotateM(m, 0, sAngle, 1f, 0f, 0f)
            System.arraycopy(m, 0, modelMatrix, 0, 16)
            drawMeshWithModel(gm.leg)
        }

        // Tattered Skirt
        bindMaterial(MaterialType.GRANDMA_DRESS)
        GLES30.glUniform3f(worldShader.uTintLoc, 1f, 1f, 1f)
        run {
            val m = baseRoot()
            Matrix.translateM(m, 0, 0f, 0.12f, 0f)
            Matrix.rotateM(m, 0, sin(wc) * 4f, 0f, 0f, 1f)
            System.arraycopy(m, 0, modelMatrix, 0, 16)
            drawMeshWithModel(gm.skirt)
        }

        // Hunched Torso + Shawl
        val torsoM = baseRoot()
        Matrix.translateM(torsoM, 0, 0f, 0.86f, 0f)
        Matrix.rotateM(torsoM, 0, hunch, 1f, 0f, 0f)
        System.arraycopy(torsoM, 0, modelMatrix, 0, 16)
        drawMeshWithModel(gm.torso)

        val shawlM = torsoM.clone()
        Matrix.translateM(shawlM, 0, 0f, 0.36f, 0f)
        System.arraycopy(shawlM, 0, modelMatrix, 0, 16)
        GLES30.glUniform3f(worldShader.uTintLoc, 0.75f, 0.68f, 0.72f)
        drawMeshWithModel(gm.shawl)

        // Left & Right Arms + Heavy Cane in Right Hand
        bindMaterial(MaterialType.GRANDMA_SKIN)
        GLES30.glUniform3f(worldShader.uTintLoc, 0.95f, 0.92f, 0.88f)
        val armForward = if (grandma.state == GrandmaState.CHASE) -52f else -18f

        // Left Arm
        run {
            val m = torsoM.clone()
            Matrix.translateM(m, 0, -0.21f, 0.54f, 0.02f)
            Matrix.rotateM(m, 0, armForward - swing * 0.7f, 1f, 0f, 0f)
            System.arraycopy(m, 0, modelMatrix, 0, 16)
            drawMeshWithModel(gm.arm)
        }

        // Right Arm + Cane
        run {
            val m = torsoM.clone()
            Matrix.translateM(m, 0, 0.21f, 0.54f, 0.02f)
            val rightArmAngle = if (grandma.state == GrandmaState.CHASE) -68f + sin(wc * 1.4f) * 24f else -24f + swing * 0.6f
            Matrix.rotateM(m, 0, rightArmAngle, 1f, 0f, 0f)
            System.arraycopy(m, 0, modelMatrix, 0, 16)
            drawMeshWithModel(gm.arm)

            // Wooden Cane attached to hand
            val caneM = m.clone()
            Matrix.translateM(caneM, 0, 0f, -0.54f, 0.04f)
            Matrix.rotateM(caneM, 0, 58f, 1f, 0f, 0f)
            System.arraycopy(caneM, 0, modelMatrix, 0, 16)
            bindMaterial(MaterialType.OAK_DOOR)
            GLES30.glUniform3f(worldShader.uTintLoc, 0.9f, 0.72f, 0.52f)
            drawMeshWithModel(gm.cane)
        }

        // Gaunt Head + Hair Bun + Glowing Eyes
        val headM = torsoM.clone()
        Matrix.translateM(headM, 0, 0f, 0.72f, 0.06f)
        Matrix.rotateM(headM, 0, grandma.headLookAngle, 0f, 1f, 0f)
        Matrix.rotateM(headM, 0, -hunch * 0.5f, 1f, 0f, 0f)

        bindMaterial(MaterialType.GRANDMA_SKIN)
        GLES30.glUniform3f(worldShader.uTintLoc, 1.0f, 0.96f, 0.90f)
        GLES30.glUniform3f(worldShader.uEmissiveLoc, 0f, 0f, 0f)
        System.arraycopy(headM, 0, modelMatrix, 0, 16)
        drawMeshWithModel(gm.head)

        // Silver Hair Bun at back of head
        val bunM = headM.clone()
        Matrix.translateM(bunM, 0, 0f, 0.05f, -0.14f)
        GLES30.glUniform3f(worldShader.uTintLoc, 0.82f, 0.84f, 0.88f)
        System.arraycopy(bunM, 0, modelMatrix, 0, 16)
        drawMeshWithModel(gm.hairBun)

        // Glowing Pale Cataract Eyes
        val eyeEmissive = if (grandma.state == GrandmaState.CHASE) Vec3(1.1f, 0.25f, 0.18f) else Vec3(0.55f, 0.65f, 0.58f)
        GLES30.glUniform3f(worldShader.uEmissiveLoc, eyeEmissive.x, eyeEmissive.y, eyeEmissive.z)
        System.arraycopy(headM, 0, modelMatrix, 0, 16)
        drawMeshWithModel(gm.eyes)
    }

    private fun renderDustMotes() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(particleShader.programId)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, sin(glTime * 0.35f) * 0.18f, sin(glTime * 0.5f) * 0.08f, cos(glTime * 0.3f) * 0.18f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(particleShader.uMVPLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform4f(particleShader.uColorLoc, 0.85f, 0.86f, 0.90f, 0.28f)
        GLES30.glUniform1f(particleShader.uPointSizeLoc, 22.0f)

        GLES30.glBindVertexArray(meshes.dustPointsVao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, meshes.dustCount)
        GLES30.glBindVertexArray(0)

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun bindMaterial(type: MaterialType) {
        val pair = textures[type] ?: return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.diffuseTexId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pair.normalRoughnessTexId)
    }

    private fun drawMeshWithModel(mesh: MeshBuffer) {
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(worldShader.uModelLoc, 1, false, modelMatrix, 0)
        GLES30.glUniformMatrix4fv(worldShader.uMVPLoc, 1, false, mvpMatrix, 0)
        drawMesh(mesh)
    }

    private fun drawMesh(mesh: MeshBuffer) {
        GLES30.glBindVertexArray(mesh.vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES30.glBindVertexArray(0)
    }

    private fun vibratePulse(ms: Long) {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Throwable) {
        }
    }
}
