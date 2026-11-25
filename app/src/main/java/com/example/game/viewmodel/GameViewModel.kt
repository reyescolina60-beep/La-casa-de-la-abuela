package com.example.game.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.example.game.audio.HorrorAudioEngine
import com.example.game.engine.HorrorGLRenderer
import com.example.game.model.Difficulty
import com.example.game.model.GameScreenState
import com.example.game.model.HudState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RunResult(
    val caught: Boolean,
    val elapsedSeconds: Float,
    val timesSpotted: Int,
    val isNewRecord: Boolean
)

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("casa_sombra_prefs", Context.MODE_PRIVATE)
    val audioEngine = HorrorAudioEngine(application)

    private val _screenState = MutableStateFlow(GameScreenState.TITLE)
    val screenState: StateFlow<GameScreenState> = _screenState.asStateFlow()

    private val _hudState = MutableStateFlow(HudState())
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _difficulty = MutableStateFlow(Difficulty.NORMAL)
    val difficulty: StateFlow<Difficulty> = _difficulty.asStateFlow()

    private val _lookSensitivity = MutableStateFlow(prefs.getFloat("sensitivity", 1.0f))
    val lookSensitivity: StateFlow<Float> = _lookSensitivity.asStateFlow()

    private val _soundEnabled = MutableStateFlow(prefs.getBoolean("sound_enabled", true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _bestTimeSeconds = MutableStateFlow<Float?>(
        prefs.getFloat("best_time", -1f).let { if (it > 0f) it else null }
    )
    val bestTimeSeconds: StateFlow<Float?> = _bestTimeSeconds.asStateFlow()

    private val _lastRunResult = MutableStateFlow<RunResult?>(null)
    val lastRunResult: StateFlow<RunResult?> = _lastRunResult.asStateFlow()

    val renderer: HorrorGLRenderer = HorrorGLRenderer(
        context = application,
        audio = audioEngine,
        onHudUpdate = { hud ->
            _hudState.value = hud
        },
        onGameOver = { caught, elapsed, spotted ->
            var isNewRecord = false
            if (!caught) {
                val prev = _bestTimeSeconds.value
                if (prev == null || elapsed < prev) {
                    _bestTimeSeconds.value = elapsed
                    prefs.edit().putFloat("best_time", elapsed).apply()
                    isNewRecord = true
                }
            }
            _lastRunResult.value = RunResult(caught, elapsed, spotted, isNewRecord)
            _screenState.value = if (caught) GameScreenState.CAUGHT else GameScreenState.ESCAPED
        }
    ).apply {
        lookSensitivity = _lookSensitivity.value
        difficulty = _difficulty.value
    }

    init {
        audioEngine.enabled = _soundEnabled.value
    }

    fun selectDifficulty(diff: Difficulty) {
        _difficulty.value = diff
        renderer.difficulty = diff
    }

    fun setSensitivity(value: Float) {
        val clamped = value.coerceIn(0.4f, 2.2f)
        _lookSensitivity.value = clamped
        renderer.lookSensitivity = clamped
        prefs.edit().putFloat("sensitivity", clamped).apply()
    }

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled
        audioEngine.enabled = enabled
        prefs.edit().putBoolean("sound_enabled", enabled).apply()
    }

    fun startNewGame() {
        _lastRunResult.value = null
        renderer.difficulty = _difficulty.value
        renderer.isPaused = false
        renderer.enqueueAction(HorrorGLRenderer.GameAction.RESET_RUN)
        _screenState.value = GameScreenState.PLAYING
    }

    fun pauseGame() {
        if (_screenState.value == GameScreenState.PLAYING) {
            renderer.isPaused = true
            renderer.moveStickX = 0f
            renderer.moveStickY = 0f
            renderer.isSprintingInput = false
            _screenState.value = GameScreenState.PAUSED
        }
    }

    fun resumeGame() {
        if (_screenState.value == GameScreenState.PAUSED) {
            renderer.isPaused = false
            _screenState.value = GameScreenState.PLAYING
        }
    }

    fun returnToTitle() {
        renderer.isPaused = true
        renderer.moveStickX = 0f
        renderer.moveStickY = 0f
        renderer.isSprintingInput = false
        _screenState.value = GameScreenState.TITLE
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
    }
}
