package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.game.engine.HorrorGLRenderer
import com.example.game.engine.HorrorGLSurface
import com.example.game.model.EscapeItemType
import com.example.game.model.GameScreenState
import com.example.game.model.HudState
import com.example.game.viewmodel.RunResult
import com.example.ui.theme.*
import kotlin.math.hypot

@Composable
fun GameScreen(
    renderer: HorrorGLRenderer,
    screenState: GameScreenState,
    hudState: HudState,
    lookSensitivity: Float,
    soundEnabled: Boolean,
    lastRunResult: RunResult?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onExitToMenu: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onToggleSound: () -> Unit
) {
    BackHandler {
        when (screenState) {
            GameScreenState.PLAYING -> {
                if (hudState.noteOverlayText != null) {
                    renderer.enqueueAction(HorrorGLRenderer.GameAction.DISMISS_NOTE)
                } else {
                    onPause()
                }
            }
            GameScreenState.PAUSED -> onResume()
            GameScreenState.CAUGHT, GameScreenState.ESCAPED -> onExitToMenu()
            GameScreenState.TITLE -> Unit
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "dangerPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (hudState.grandmaChasing) 380 else 750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live 3D OpenGL ES 3.0 Viewport
        HorrorGLSurface(
            renderer = renderer,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Horror Vignette & Heartbeat Danger Edge Glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val maxR = hypot(w, h) * 0.58f
            val center = Offset(w * 0.5f, h * 0.5f)

            // Always-on atmospheric dark corner vignette
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0xAA030407)),
                    center = center,
                    radius = maxR
                )
            )

            // Danger / Chase crimson edge pulse
            val danger = hudState.dangerProximity
            if (danger > 0.15f) {
                val redAlpha = (danger * 0.52f * pulseAlpha).coerceIn(0f, 0.58f)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0xFF9E1115).copy(alpha = redAlpha)),
                        center = center,
                        radius = maxR * 0.92f
                    )
                )
            }
        }

        // 3. Touch Controls Layer (active when playing)
        if (screenState == GameScreenState.PLAYING && hudState.noteOverlayText == null) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left 42%: Virtual Analog Movement Joystick
                VirtualJoystickZone(
                    enabled = !hudState.isHiding,
                    onStickMove = { x, y ->
                        renderer.moveStickX = x
                        renderer.moveStickY = y
                    },
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                )

                // Right 58%: Smooth Touch-Drag Camera Look
                Box(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                renderer.addLookDelta(dragAmount.x, dragAmount.y)
                            }
                        }
                )
            }
        }

        // 4. Center Reticle + Contextual Interaction Prompt
        if (screenState == GameScreenState.PLAYING && hudState.noteOverlayText == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(if (hudState.canInteract) 10.dp else 6.dp)
                        .background(
                            color = if (hudState.canInteract) ManorBrass else Color.White.copy(alpha = 0.72f),
                            shape = CircleShape
                        )
                )
                AnimatedVisibility(
                    visible = hudState.interactionPrompt != null,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(150))
                ) {
                    Surface(
                        onClick = { renderer.enqueueAction(HorrorGLRenderer.GameAction.INTERACT) },
                        color = ManorObsidian.copy(alpha = 0.84f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, ManorBrass.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .testTag("reticle_prompt_chip")
                    ) {
                        Text(
                            text = hudState.interactionPrompt.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            color = ManorParchment,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 5. Top HUD Bar (Safe Drawing Insets)
        if (screenState == GameScreenState.PLAYING) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Floor, Timer & Stamina
                Surface(
                    color = ManorObsidian.copy(alpha = 0.76f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ManorBrassDim.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = ManorBrass,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${hudState.playerFloor}.º Piso",
                                style = MaterialTheme.typography.labelLarge,
                                color = ManorParchment
                            )
                            Text(
                                text = "• ${formatTime(hudState.elapsedSeconds)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ManorMist
                            )
                        }
                        LinearProgressIndicator(
                            progress = { hudState.stamina },
                            color = if (hudState.stamina < 0.25f) ManorCrimsonBright else ManorBrass,
                            trackColor = ManorSlate,
                            modifier = Modifier
                                .width(128.dp)
                                .height(4.dp)
                        )
                    }
                }

                // Exit Lock Progress Pill
                Surface(
                    color = ManorObsidian.copy(alpha = 0.76f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (hudState.exitProgress.isFullyUnlocked) ManorEmerald else ManorBrassDim.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (hudState.exitProgress.isFullyUnlocked) ManorEmerald else ManorBrass,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Puerta 1.º Piso: ${hudState.exitProgress.unlockedSteps}/3",
                            style = MaterialTheme.typography.labelLarge,
                            color = ManorParchment
                        )
                    }
                }

                // Pause Button
                FilledIconButton(
                    onClick = onPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ManorObsidian.copy(alpha = 0.78f),
                        contentColor = ManorParchment
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pause_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = stringResource(R.string.pause_title)
                    )
                }
            }

            // Status Banner (top-center below HUD)
            AnimatedVisibility(
                visible = hudState.statusBanner != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 72.dp, start = 24.dp, end = 24.dp)
            ) {
                Surface(
                    color = if (hudState.grandmaChasing) ManorCrimson.copy(alpha = 0.9f) else ManorCharcoal.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ManorBrassDim.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = hudState.statusBanner.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ManorParchment,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 6. Bottom HUD: Inventory + Action Buttons (respects navigationBars insets)
        if (screenState == GameScreenState.PLAYING && hudState.noteOverlayText == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Bottom-Center: Collected Escape Tools
                Surface(
                    color = ManorObsidian.copy(alpha = 0.76f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, ManorBrassDim.copy(alpha = 0.35f)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InventorySlot(
                            icon = Icons.Default.Build,
                            label = "Palanca",
                            owned = hudState.collectedItems.contains(EscapeItemType.CROWBAR)
                        )
                        InventorySlot(
                            icon = Icons.Default.Key,
                            label = "Bronce",
                            owned = hudState.collectedItems.contains(EscapeItemType.BRASS_KEY)
                        )
                        InventorySlot(
                            icon = Icons.Default.VpnKey,
                            label = "Maestra",
                            owned = hudState.collectedItems.contains(EscapeItemType.MASTER_KEY)
                        )
                    }
                }

                // Bottom-Right: Action Buttons (48dp+ touch targets)
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hudState.canInteract) {
                        ExtendedFloatingActionButton(
                            onClick = { renderer.enqueueAction(HorrorGLRenderer.GameAction.INTERACT) },
                            containerColor = ManorBrass,
                            contentColor = ManorObsidian,
                            modifier = Modifier
                                .heightIn(min = 52.dp)
                                .testTag("interact_button")
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hudState.isHiding) stringResource(R.string.leave_hiding_label)
                                else stringResource(R.string.interact_label),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Crouch toggle
                        HudToggleButton(
                            icon = Icons.Default.Height,
                            contentDesc = stringResource(R.string.crouch_label),
                            active = hudState.isCrouching,
                            tag = "crouch_button",
                            onClick = { renderer.enqueueAction(HorrorGLRenderer.GameAction.TOGGLE_CROUCH) }
                        )
                        // Sprint toggle
                        HudToggleButton(
                            icon = Icons.AutoMirrored.Filled.DirectionsRun,
                            contentDesc = stringResource(R.string.sprint_label),
                            active = hudState.isSprinting,
                            tag = "sprint_button",
                            onClick = { renderer.isSprintingInput = !renderer.isSprintingInput }
                        )
                        // Flashlight toggle
                        HudToggleButton(
                            icon = if (hudState.isFlashlightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            contentDesc = stringResource(R.string.flashlight_label),
                            active = hudState.isFlashlightOn,
                            tag = "flashlight_button",
                            onClick = { renderer.enqueueAction(HorrorGLRenderer.GameAction.TOGGLE_FLASHLIGHT) }
                        )
                    }
                }
            }
        }

        // 7. Survival Note Modal
        if (hudState.noteOverlayText != null && screenState == GameScreenState.PLAYING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xBB050508))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFF231D16),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.5.dp, ManorBrass),
                    modifier = Modifier.widthIn(max = 460.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "NOTA EN LA MESITA",
                            style = MaterialTheme.typography.headlineMedium,
                            color = ManorBrass
                        )
                        Text(
                            text = hudState.noteOverlayText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = ManorParchment
                        )
                        Button(
                            onClick = { renderer.enqueueAction(HorrorGLRenderer.GameAction.DISMISS_NOTE) },
                            colors = ButtonDefaults.buttonColors(containerColor = ManorBrass, contentColor = ManorObsidian),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("dismiss_note_button")
                        ) {
                            Text("Cerrar Nota", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 8. Pause Overlay
        if (screenState == GameScreenState.PAUSED) {
            PauseOverlay(
                lookSensitivity = lookSensitivity,
                soundEnabled = soundEnabled,
                onSensitivityChange = onSensitivityChange,
                onToggleSound = onToggleSound,
                onResume = onResume,
                onRestart = onRestart,
                onExitToMenu = onExitToMenu
            )
        }

        // 9. Caught / Escaped End Screen
        if (screenState == GameScreenState.CAUGHT || screenState == GameScreenState.ESCAPED) {
            EndRunOverlay(
                caught = screenState == GameScreenState.CAUGHT,
                result = lastRunResult,
                onRetry = onRestart,
                onExitToMenu = onExitToMenu
            )
        }
    }
}

@Composable
private fun VirtualJoystickZone(
    enabled: Boolean,
    onStickMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var origin by remember { mutableStateOf<Offset?>(null) }
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadiusPx = 110f

    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) {
                origin = null
                knobOffset = Offset.Zero
                onStickMove(0f, 0f)
                return@pointerInput
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                origin = down.position
                knobOffset = Offset.Zero
                onStickMove(0f, 0f)

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        origin = null
                        knobOffset = Offset.Zero
                        onStickMove(0f, 0f)
                        break
                    }
                    change.consume()
                    val delta = change.position - (origin ?: down.position)
                    val dist = hypot(delta.x, delta.y)
                    val clamped = if (dist > maxRadiusPx) {
                        Offset(delta.x / dist * maxRadiusPx, delta.y / dist * maxRadiusPx)
                    } else {
                        delta
                    }
                    knobOffset = clamped
                    // Screen Y down is negative forward; invert Y so dragging up moves forward!
                    onStickMove(clamped.x / maxRadiusPx, -clamped.y / maxRadiusPx)
                }
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseCenter = origin ?: Offset(115.dp.toPx(), size.height - 125.dp.toPx())
            val alpha = if (origin != null) 0.85f else 0.32f
            drawCircle(
                color = Color(0xFFD2A85C).copy(alpha = alpha * 0.22f),
                radius = 52.dp.toPx(),
                center = baseCenter
            )
            drawCircle(
                color = Color(0xFFD2A85C).copy(alpha = alpha * 0.75f),
                radius = 52.dp.toPx(),
                center = baseCenter,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color(0xFFEAE3D5).copy(alpha = alpha),
                radius = 22.dp.toPx(),
                center = baseCenter + knobOffset
            )
        }
    }
}

@Composable
private fun InventorySlot(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    owned: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(
                color = if (owned) ManorBrass.copy(alpha = 0.22f) else ManorSlate.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (owned) ManorBrass else ManorMist.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (owned) ManorParchment else ManorMist.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun HudToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) ManorBrass else ManorObsidian.copy(alpha = 0.8f),
            contentColor = if (active) ManorObsidian else ManorParchment
        ),
        modifier = Modifier
            .size(52.dp)
            .testTag(tag)
    ) {
        Icon(imageVector = icon, contentDescription = contentDesc)
    }
}

@Composable
private fun PauseOverlay(
    lookSensitivity: Float,
    soundEnabled: Boolean,
    onSensitivityChange: (Float) -> Unit,
    onToggleSound: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onExitToMenu: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC06070A))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = ManorCharcoal,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, ManorBrassDim),
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.pause_title).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = ManorBrass
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${stringResource(R.string.sensitivity_label)}: ${String.format("%.1fx", lookSensitivity)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ManorParchment
                    )
                    Slider(
                        value = lookSensitivity,
                        onValueChange = onSensitivityChange,
                        valueRange = 0.4f..2.2f,
                        colors = SliderDefaults.colors(
                            thumbColor = ManorBrass,
                            activeTrackColor = ManorBrass
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (soundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = null,
                            tint = ManorBrass
                        )
                        Text(
                            text = stringResource(R.string.sound_label),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ManorParchment
                        )
                    }
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = { onToggleSound() }
                    )
                }

                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = ManorBrass, contentColor = ManorObsidian),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("resume_button")
                ) {
                    Text(stringResource(R.string.resume_game), fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onRestart,
                    border = BorderStroke(1.dp, ManorBrassDim),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ManorParchment),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("restart_button")
                ) {
                    Text(stringResource(R.string.restart_game))
                }

                TextButton(
                    onClick = onExitToMenu,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("exit_menu_button")
                ) {
                    Text(stringResource(R.string.exit_to_menu), color = ManorMist)
                }
            }
        }
    }
}

@Composable
private fun EndRunOverlay(
    caught: Boolean,
    result: RunResult?,
    onRetry: () -> Unit,
    onExitToMenu: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = if (caught) {
                        listOf(Color(0xDD42090B), Color(0xF5070406))
                    } else {
                        listOf(Color(0xCC0F2820), Color(0xF506080B))
                    }
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = ManorCharcoal.copy(alpha = 0.94f),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.5.dp, if (caught) ManorCrimsonBright else ManorEmerald),
            modifier = Modifier.widthIn(max = 440.dp)
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (caught) stringResource(R.string.caught_title) else stringResource(R.string.escaped_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (caught) ManorCrimsonBright else ManorEmerald,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (caught) stringResource(R.string.caught_subtitle) else stringResource(R.string.escaped_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ManorParchment.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center
                )

                if (result != null) {
                    Surface(
                        color = ManorSlate,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Tiempo: ${formatTime(result.elapsedSeconds)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = ManorParchment
                            )
                            Text(
                                text = "Veces descubierto: ${result.timesSpotted}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ManorMist
                            )
                            if (result.isNewRecord) {
                                Text(
                                    text = "¡Nuevo récord de escape!",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ManorBrass
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (caught) ManorCrimson else ManorEmerald,
                        contentColor = ManorParchment
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("retry_button")
                ) {
                    Text(stringResource(R.string.retry_button), fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onExitToMenu,
                    border = BorderStroke(1.dp, ManorBrassDim),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ManorParchment),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("end_menu_button")
                ) {
                    Text(stringResource(R.string.exit_to_menu))
                }
            }
        }
    }
}
