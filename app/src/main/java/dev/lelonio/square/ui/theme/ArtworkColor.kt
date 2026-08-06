package dev.lelonio.square.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The dominant colour of an artwork URL, for seeding the theme.
 *
 * Results are memoised per URL: the same cover is asked for by the mini player,
 * the full player and the theme at once, and decoding a bitmap three times per
 * track change is wasteful.
 */
@Composable
fun rememberArtworkColor(artworkUrl: String?): State<Color?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) {
            state.value = null
            return@LaunchedEffect
        }
        cached[artworkUrl]?.let {
            state.value = it
            return@LaunchedEffect
        }
        state.value = extractDominant(context, artworkUrl)?.also { cached[artworkUrl] = it }
    }
    return state
}

private val cached = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Color>?) = size > 64
}

private suspend fun extractDominant(context: Context, url: String): Color? =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                // Palette only needs colour proportions, so decode small: a
                // full-size cover costs far more memory for the same answer.
                .size(96)
                .allowHardware(false)
                .build()

            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
                ?.drawable
                ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                ?: return@runCatching null

            val palette = Palette.from(bitmap).clearFilters().generate()
            val rgb = palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: return@runCatching null

            Color(rgb)
        }.getOrNull()
    }
