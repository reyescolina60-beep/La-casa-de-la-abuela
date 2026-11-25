package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.game.model.GameScreenState
import com.example.game.viewmodel.GameViewModel
import com.example.ui.screens.GameScreen
import com.example.ui.screens.TitleScreen
import com.example.ui.theme.CasaSombraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            CasaSombraTheme {
                val vm: GameViewModel = viewModel()
                val screenState by vm.screenState.collectAsStateWithLifecycle()
                val hudState by vm.hudState.collectAsStateWithLifecycle()
                val difficulty by vm.difficulty.collectAsStateWithLifecycle()
                val sensitivity by vm.lookSensitivity.collectAsStateWithLifecycle()
                val soundEnabled by vm.soundEnabled.collectAsStateWithLifecycle()
                val bestTime by vm.bestTimeSeconds.collectAsStateWithLifecycle()
                val lastResult by vm.lastRunResult.collectAsStateWithLifecycle()

                if (screenState == GameScreenState.TITLE) {
                    TitleScreen(
                        selectedDifficulty = difficulty,
                        bestTimeSeconds = bestTime,
                        soundEnabled = soundEnabled,
                        onSelectDifficulty = vm::selectDifficulty,
                        onToggleSound = { vm.setSoundEnabled(!soundEnabled) },
                        onStartGame = vm::startNewGame
                    )
                } else {
                    GameScreen(
                        renderer = vm.renderer,
                        screenState = screenState,
                        hudState = hudState,
                        lookSensitivity = sensitivity,
                        soundEnabled = soundEnabled,
                        lastRunResult = lastResult,
                        onPause = vm::pauseGame,
                        onResume = vm::resumeGame,
                        onRestart = vm::startNewGame,
                        onExitToMenu = vm::returnToTitle,
                        onSensitivityChange = vm::setSensitivity,
                        onToggleSound = { vm.setSoundEnabled(!soundEnabled) }
                    )
                }
            }
        }
    }
}
