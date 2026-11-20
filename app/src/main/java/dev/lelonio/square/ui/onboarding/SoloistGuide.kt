package dev.lelonio.square.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import dev.lelonio.square.R

/**
 * Soloist's three steps, one screen each, over the whole app: what choosing
 * Soloist in the settings opens. The same pages the welcome guide shows after
 * the same choice, on the same glass.
 */
@Composable
fun SoloistGuide(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val soloist = dev.lelonio.square.ui.settings.rememberSoloistSetup()
        val pages = listOf(Page.SOLOIST_KEY, Page.SOLOIST_DOWNLOAD, Page.SOLOIST_SIGN_IN)
        var step by remember { mutableIntStateOf(0) }
        val last = pages.lastIndex
        val done = soloistStepDone(pages[step], soloist)

        SetupScaffold(
            top = {
                if (step > 0) {
                    RoundButton(PhosphorIcons.Regular.ArrowLeft, stringResource(R.string.back)) { step-- }
                }
                Spacer(Modifier.weight(1f))
                QuietButton(stringResource(R.string.close), onClick = onClose)
            },
            primary = {
                PrimaryButton(
                    stringResource(if (step == last) R.string.setup_done else R.string.next_step),
                    enabled = done,
                    onClick = { if (step == last) onClose() else step++ },
                )
            },
            progress = step to pages.size,
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val width = { w: Int -> if (forward) w / 6 else -w / 6 }
                    (slideInHorizontally(tween(280), width) + fadeIn(tween(220, delayMillis = 60)))
                        .togetherWith(slideOutHorizontally(tween(200)) { w -> -width(w) } + fadeOut(tween(120)))
                        .using(androidx.compose.animation.SizeTransform(clip = false))
                },
                label = "soloistGuide",
                modifier = Modifier.fillMaxWidth(),
            ) { shown ->
                Column(Modifier.fillMaxWidth()) {
                    when (pages[shown]) {
                        Page.SOLOIST_KEY -> SoloistKeyStep(soloist)
                        Page.SOLOIST_DOWNLOAD -> SoloistDownloadStep(soloist)
                        else -> SoloistSignInStep(soloist, onDone = onClose)
                    }
                }
            }
        }
    }
}
