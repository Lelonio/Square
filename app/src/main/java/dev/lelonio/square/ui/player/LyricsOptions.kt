package dev.lelonio.square.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Quotes
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

/**
 * The way to the other sources and styles: a round button in the column of
 * the lyrics' controls, above the translation, like them. It used to be a
 * labelled chip on its own in the other corner.
 */
@Composable
fun LyricsSourceChip(source: String?, backdrop: Backdrop, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = LyricsSource.entries.firstOrNull { it.name == source }?.label
        ?: stringResource(R.string.lyrics_sources)
    GlassSurface(
        backdrop = backdrop,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = modifier
            .size(44.dp)
            .pressable(onClick, shape = androidx.compose.foundation.shape.CircleShape),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                PhosphorIcons.Regular.Quotes,
                // Says which source it is, for whoever cannot see the words.
                contentDescription = label,
                tint = GlassInk,
                modifier = Modifier.size(20.dp),
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
    val sources = remember(query.uri) { LyricsLibrary.survey(query).keys.toList() }
    // Absent: still asking. Present and null: asked, and it has nothing.
    val answers = remember(query) { mutableStateMapOf<LyricsSource, Lyrics?>() }
    val asked = remember(query) { mutableStateMapOf<LyricsSource, Boolean>() }
    // The song's own survey: what has answered already is there at once, and
    // only what is still on its way is waited for.
    LaunchedEffect(query) {
        LyricsLibrary.survey(query).forEach { (source, answer) ->
            launch {
                answers[source] = runCatching { answer.await() }.getOrNull()
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
                // What has words for this song, in the order they are tried;
                // the rest, still asking or with nothing, folded away under
                // one row, so the choice is between real answers. A source
                // that answers moves up as it does.
                val row: @Composable (LyricsSource) -> Unit = { source ->
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
                sources.forEach { source ->
                    AnimatedVisibility(
                        visible = answers[source] != null,
                        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                    ) { row(source) }
                }
                val others = sources.filter { answers[it] == null }
                if (others.isNotEmpty()) {
                    var expanded by remember(query.uri) { mutableStateOf(false) }
                    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "caret")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable({ expanded = !expanded })
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.lyrics_more_sources),
                            style = MaterialTheme.typography.bodyLarge,
                            color = GlassInkDim,
                            modifier = Modifier.weight(1f),
                        )
                        // Said while any of them is still asking: one may yet
                        // move up into the list.
                        if (!expanded && others.any { asked[it] != true }) {
                            CircularProgressIndicator(
                                color = GlassInkDim,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Icon(
                            PhosphorIcons.Regular.CaretDown,
                            contentDescription = null,
                            tint = GlassInkDim,
                            modifier = Modifier.size(18.dp).rotate(turn),
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
                    ) {
                        Column { others.forEach { row(it) } }
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

/**
 * "Lyrics from Spicy Lyrics · uploaded by …, made by …", each name a link to
 * the person's page, as the source asks; the names only when it gives them.
 */
@Composable
fun LyricsCreditLine(credit: dev.lelonio.square.data.LyricsCredit, modifier: Modifier = Modifier) {
    val from = stringResource(R.string.lyrics_from, credit.provider)
    val uploadedBy = stringResource(R.string.lyrics_uploaded_by)
    val madeBy = stringResource(R.string.lyrics_made_by)
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(from)
        fun person(label: String, name: String?, url: String?) {
            if (name == null) return
            append(" · ")
            append(label)
            append(" ")
            if (url != null) {
                withLink(
                    androidx.compose.ui.text.LinkAnnotation.Url(
                        url,
                        androidx.compose.ui.text.TextLinkStyles(
                            androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold),
                        ),
                    ),
                ) { append(name) }
            } else {
                append(name)
            }
        }
        person(uploadedBy, credit.uploader, credit.uploaderUrl)
        person(madeBy, credit.maker, credit.makerUrl)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = GlassInkDim,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier,
    )
}
