package dev.lelonio.square.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.lelonio.square.ui.onboarding.BackendChoiceScreen
import dev.lelonio.square.ui.theme.SquareTheme

/**
 * The first screen of a new install, shown on its own without touching the
 * install's own choice: `adb shell am start -n dev.lelonio.square/.dev.SetupPreview`.
 * Picking a service just closes it.
 */
class SetupPreview : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SquareTheme(darkTheme = true) {
                BackendChoiceScreen(onChoose = { finish() })
            }
        }
    }
}
