package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.game.model.Difficulty
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun TitleScreen(
    selectedDifficulty: Difficulty,
    bestTimeSeconds: Float?,
    soundEnabled: Boolean,
    onSelectDifficulty: (Difficulty) -> Unit,
    onToggleSound: () -> Unit,
    onStartGame: () -> Unit
) {
    var showGuide by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ManorObsidian)
    ) {
        // Generated horror manor artwork backdrop
        Image(
            painter = painterResource(id = R.drawable.img_manor_title),
            contentDescription = "Interior de la mansión de dos pisos",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Moody vertical & radial scrim for contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC06070A),
                            Color(0x77090A0E),
                            Color(0xEE08090C)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar: best record + sound toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = ManorCharcoal.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, ManorBrassDim.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = ManorBrass,
                            modifier = Modifier.size(16.dp)
                        )
                        val recordStr = bestTimeSeconds?.let { formatTime(it) }
                            ?: stringResource(R.string.no_record_yet)
                        Text(
                            text = "${stringResource(R.string.best_time_label)}: $recordStr",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ManorParchment
                        )
                    }
                }

                IconButton(
                    onClick = onToggleSound,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("title_sound_toggle")
                ) {
                    Icon(
                        imageVector = if (soundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = stringResource(R.string.sound_label),
                        tint = ManorParchment
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Hero Title Block
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 560.dp)
            ) {
                Text(
                    text = "CASA SOMBRA",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 42.sp,
                        letterSpacing = 4.sp
                    ),
                    color = ManorParchment,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.game_subtitle).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = ManorBrass,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Despiertas atrapado en el segundo piso. La abuela patrulla el primer piso. Encuentra las herramientas y abre la puerta principal antes de que te escuche.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ManorParchment.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Difficulty & Action Panel
            Surface(
                color = ManorCharcoal.copy(alpha = 0.88f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, ManorBrassDim.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.difficulty_label).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        color = ManorMist
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Difficulty.entries.forEach { diff ->
                            val selected = diff == selectedDifficulty
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectDifficulty(diff) },
                                label = {
                                    Text(
                                        text = diff.label,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = ManorSlate,
                                    labelColor = ManorMist,
                                    selectedContainerColor = if (diff == Difficulty.NIGHTMARE) ManorCrimson else ManorBrass,
                                    selectedLabelColor = if (diff == Difficulty.NIGHTMARE) ManorParchment else ManorObsidian
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = ManorBrassDim.copy(alpha = 0.4f),
                                    selectedBorderColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("difficulty_${diff.name.lowercase()}")
                            )
                        }
                    }

                    Button(
                        onClick = onStartGame,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ManorCrimson,
                            contentColor = ManorParchment
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("start_game_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.start_game),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    OutlinedButton(
                        onClick = { showGuide = true },
                        border = BorderStroke(1.dp, ManorBrassDim),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ManorBrass),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("how_to_play_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.how_to_play))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showGuide) {
            AlertDialog(
                onDismissRequest = { showGuide = false },
                containerColor = ManorCharcoal,
                titleContentColor = ManorBrass,
                textContentColor = ManorParchment,
                title = {
                    Text(
                        text = stringResource(R.string.how_to_play),
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GuideRow(
                            icon = Icons.Default.DoorFront,
                            text = "Casa de 2 pisos: siempre apareces en el dormitorio del 2.º piso y la abuela siempre aparece en el 1.º piso."
                        )
                        GuideRow(
                            icon = Icons.Default.Key,
                            text = "Busca la Palanca de Hierro, la Llave de Bronce y la Llave Maestra repartidas por las habitaciones para abrir la puerta principal del 1.º piso."
                        )
                        GuideRow(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            text = "Correr y abrir puertas hace ruido y atrae a la abuela. Agáchate para caminar en silencio o escóndete dentro de un armario."
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGuide = false }) {
                        Text("Entendido", color = ManorBrass)
                    }
                }
            )
        }
    }
}

@Composable
private fun GuideRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ManorBrass,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = ManorParchment.copy(alpha = 0.9f)
        )
    }
}

fun formatTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val mins = total / 60
    val secs = total % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
