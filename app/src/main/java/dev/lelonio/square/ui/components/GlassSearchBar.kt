// liquid_glass_widgets' GlassSearchBar, MIT:
//
//   lib/widgets/input/glass_search_bar.dart
//   lib/widgets/input/glass_text_field.dart (GlassTextField.search)
//   https://github.com/sdegenaar/liquid_glass_widgets
//
// See glass/morph/LICENSE-liquid-glass.txt.
//
// A capsule of glass 44 high, the magnifier 20 across on its left with 8 to
// the words, the words at 16, a clear button that fades and grows in once
// there is something to clear, and a cancel disc beside it while it has
// focus, where it is asked for. A press swells it to 1.03 and lights it
// under the finger, as upstream's field does.

package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.XCircle
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.glassGlowColor
import dev.lelonio.square.ui.glass.isLightGlassPage
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.rememberGlassPressState

/** Upstream's `Curves.easeOutCubic`. */
private val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

@Composable
fun GlassSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search),
    /** What the glass refracts; the app's own page where none is given. */
    backdrop: Backdrop? = null,
    height: Dp = 44.dp,
    /** Upstream's `showsCancelButton`: a disc beside the field while it has focus. */
    showsCancelButton: Boolean = false,
    onCancel: (() -> Unit)? = null,
    onSubmit: ((String) -> Unit)? = null,
    /** Focus on arrival, for a field opened by a button. */
    autoFocus: Boolean = false,
    /** How present the contents are, read while drawing. */
    contentAlpha: () -> Float = { 1f },
    /** The magnifier; null for a plain field, upstream's GlassTextField. */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = PhosphorIcons.Regular.MagnifyingGlass,
    imeAction: ImeAction = ImeAction.Search,
) {
    val light = isLightGlassPage()
    val surfaceBackdrop = backdrop ?: LocalAppBackdrop.current
    // Upstream's glass-style colours.
    val iconColor = if (light) Color(0x99000000) else Color(0x99FFFFFF)
    val textColor = if (light) Color.Black.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.9f)
    val placeholderColor = if (light) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)
    val cancelColor = if (light) Color(0xE6000000) else Color(0xE6FFFFFF)
    val shape = RoundedCornerShape(percent = 50)

    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    // Held with a selection, so a field that opens on words already typed puts
    // the cursor after them. Kept in step with the query from outside.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    LaunchedEffect(query) {
        if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    }

    // GlassTextField's press: 1.03 over 220 ms, eased out, while a finger is down.
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 1.03f else 1f,
        tween(220, easing = EaseOutCubic),
        label = "searchPress",
    )
    val press = rememberGlassPressState()

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressed = true
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                        pressed = false
                    }
                }
                .then(press.gestureModifier)
                .liquidGlass(
                    config = LocalGlassEffectConfig.current,
                    shape = shape,
                    ownBackdrop = surfaceBackdrop,
                )
                // Upstream's GlassGlow at the field's radius of 1.5.
                .then(press.glowModifier(glassGlowColor(light), radius = 1.5f))
                .graphicsLayer { alpha = contentAlpha() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = if (icon != null) 8.dp else 0.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val style = TextStyle(fontSize = 16.sp, lineHeight = 1.2.em, color = textColor)
                if (field.text.isEmpty()) {
                    Text(
                        placeholder,
                        style = style.copy(color = placeholderColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != query) onQuery(it.text)
                    },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSubmit?.invoke(field.text) },
                        onDone = { onSubmit?.invoke(field.text) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            AnimatedVisibility(
                visible = field.text.isNotEmpty(),
                enter = fadeIn(tween(180)) + scaleIn(tween(180)),
                exit = fadeOut(tween(180)) + scaleOut(tween(180)),
            ) {
                Icon(
                    PhosphorIcons.Fill.XCircle,
                    contentDescription = stringResource(R.string.clear),
                    tint = iconColor,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    field = TextFieldValue("")
                                    onQuery("")
                                }
                            }
                        },
                )
            }
        }
        AnimatedVisibility(
            visible = showsCancelButton && focused,
            enter = fadeIn(tween(180)) + androidx.compose.animation.expandHorizontally(tween(250)),
            exit = fadeOut(tween(180)) + androidx.compose.animation.shrinkHorizontally(tween(250)),
        ) {
            LiquidButton(
                onClick = {
                    field = TextFieldValue("")
                    focusManager.clearFocus()
                    onCancel?.invoke()
                    onQuery("")
                },
                backdrop = surfaceBackdrop,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(height),
                contentHeight = height,
                contentPadding = 0.dp,
            ) {
                Icon(
                    PhosphorIcons.Regular.X,
                    contentDescription = stringResource(R.string.cancel),
                    tint = cancelColor,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
