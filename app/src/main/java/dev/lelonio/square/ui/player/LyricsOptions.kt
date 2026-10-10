package dev.lelonio.square.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.R
import dev.lelonio.square.backend.lyrics.LyricsLibrary
import dev.lelonio.square.backend.lyrics.LyricsQuery
import dev.lelonio.square.backend.lyrics.LyricsSource
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.data.PreferencesStore.LyricsStyle
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.pressable
import kotlinx.coroutines.launch

/** Where the words on screen came from, and the way to the others. */
@Composable
fun LyricsSourceChip(source: String?, backdrop: Backdrop, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = LyricsSource.entries.firstOrNull { it.name == source }?.label
        ?: stringResource(R.string.lyrics_options)
    GlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(50),
        modifier = modifier.pressable(onClick, shape = RoundedCornerShape(50)),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = GlassInk, maxLines = 1)
            Icon(
                PhosphorIcons.Regular.CaretDown,
                contentDescription = null,
                tint = GlassInkDim,
                modifier = Modifier.padding(start = 6.dp).size(14.dp),
            )
        }
    }
}

/**
 * Every source asked at once for this song, each saying what it has, and the
 * two ways the words can be drawn.
 *
 * Asked when opened rather than kept from before: the order stops at the first
 * source with anything, so what the others hold is only known by asking them.
 * Each row fills in as its own answer arrives.
 */
@Composable
fun LyricsOptionsPanel(
    query: LyricsQuery,
    current: String?,
    style: LyricsStyle,
    onStyle: (LyricsStyle) -> Unit,
    onChoose: (Lyrics) -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    BackHandler(onBack = onDismiss)
    val sources = remember(query.uri) { LyricsLibrary.sourcesFor(query.uri) }
    // Absent: still asking. Present and null: asked, and it has nothing.
    val answers = remember(query) { mutableStateMapOf<LyricsSource, Lyrics?>() }
    val asked = remember(query) { mutableStateMapOf<LyricsSource, Boolean>() }
    LaunchedEffect(query) {
        sources.forEach { source ->
            launch {
                val found = kotlinx.coroutines.withTimeoutOrNull(12_000) { LyricsLibrary.fetch(source, query) }
                answers[source] = found
                asked[source] = true
            }
        }
    }
    val scope = rememberCoroutineScope()

    // In the lyrics' own place rather than over them: the words make room
    // for this and come back when it closes, the way the player's other
    // panels take the stage in turn.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 10.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.lyrics_options),
                style = MaterialTheme.typography.titleLarge,
                color = GlassInk,
                modifier = Modifier.weight(1f),
            )
            Icon(
                PhosphorIcons.Regular.X,
                contentDescription = stringResource(R.string.close),
                tint = GlassInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .pressable(onDismiss, shape = RoundedCornerShape(50))
                    .padding(10.dp)
                    .size(20.dp),
            )
        }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
            ) {
                Heading(stringResource(R.string.lyrics_source_title))
                sources.forEach { source ->
                    val done = asked[source] == true
                    val found = answers[source]
                    SourceRow(
                        name = source.label,
                        status = when {
                            !done -> null
                            found == null -> stringResource(R.string.lyrics_nothing)
                            found.lines.any { it.words.isNotEmpty() } -> stringResource(R.string.lyrics_word)
                            found.synced -> stringResource(R.string.lyrics_line)
                            else -> stringResource(R.string.lyrics_unsynced)
                        },
                        selected = source.name == current,
                        enabled = found != null,
                    ) {
                        val chosen = found ?: return@SourceRow
                        scope.launch {
                            LyricsLibrary.choose(query.uri, chosen)
                            onChoose(chosen)
                            onDismiss()
                        }
                    }
                }
                Heading(stringResource(R.string.lyrics_style_title), top = 10.dp)
                // Two side by side, on one line: a choice of two does not need
                // two rows, and the panel has to fit the lyrics' own space.
                Row(
                    Modifier.padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LyricsStyle.entries.forEach { option ->
                        val chosen = option == style
                        Text(
                            stringResource(
                                when (option) {
                                    LyricsStyle.ANIMATED -> R.string.lyrics_style_animated
                                    LyricsStyle.CLASSIC -> R.string.lyrics_style_classic
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (chosen) FontWeight.SemiBold else null,
                            color = if (chosen) Color.Black else GlassInk,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(if (chosen) GlassInk else GlassInk.copy(alpha = 0.12f))
                                .pressable({ onStyle(option) }, shape = RoundedCornerShape(50))
                                .padding(vertical = 9.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
    }
}

@Composable
private fun Heading(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = GlassInkDim,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = top + 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SourceRow(name: String, status: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, enabled = enabled)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (enabled) GlassInk else GlassInkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            status == null -> CircularProgressIndicator(
                color = GlassInkDim,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            status.isNotEmpty() -> Text(status, style = MaterialTheme.typography.bodySmall, color = GlassInkDim, maxLines = 1)
        }
        if (selected) {
            Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = GlassInk,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
