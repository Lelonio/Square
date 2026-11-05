package dev.lelonio.square.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.backdrop.Backdrop

/**
 * The field that filters a list: the library's own lists, and the songs
 * inside a record. Upstream's GlassSearchBar, as every search field in the
 * app is; see GlassSearchBar.
 */
@Composable
fun ListSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_in_tracks),
    /** What the glass refracts; the app's own page where none is given. */
    backdrop: Backdrop? = null,
    height: Dp = 44.dp,
    /** Focus on arrival, for the field that opens out of a button. */
    autoFocus: Boolean = false,
    /** How present the contents are, read on every frame. */
    contentAlpha: () -> Float = { 1f },
) {
    GlassSearchBar(
        query = query,
        onQuery = onQuery,
        modifier = modifier,
        placeholder = placeholder,
        backdrop = backdrop,
        height = height,
        autoFocus = autoFocus,
        contentAlpha = contentAlpha,
    )
}
