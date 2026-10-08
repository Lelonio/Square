package dev.lelonio.square.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import dev.lelonio.square.ui.components.menuSkin
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.data.ListeningEvent
import dev.lelonio.square.data.ListeningHistory
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MonthlyListeningDialog(events: List<ListeningEvent>, backdrop: Backdrop, onDismiss: () -> Unit) {
    val current = YearMonth.now()
    var month by remember { mutableStateOf(current) }

    val summary = remember(events, month) { ListeningHistory.summarize(events, month) }
    val first = remember(events) {
        events.minOfOrNull { it.startedAt }?.let {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        } ?: current
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(Modifier.padding(20.dp).fillMaxWidth().heightIn(max = maxHeight)
        .menuSkin(RoundedCornerShape(28.dp)).verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recap_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, color = Ink)
            LiquidButton(onClick = onDismiss, backdrop = backdrop, flat = true,
                modifier = Modifier.size(44.dp), contentHeight = 44.dp, contentPadding = 0.dp) {
                Icon(PhosphorIcons.Regular.X, contentDescription = stringResource(R.string.close),
                    tint = Ink, modifier = Modifier.size(20.dp))
            }
        }
        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())),
            style = MaterialTheme.typography.titleMedium, color = InkDim)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            summary.tracks.firstOrNull()?.first?.let { track ->
                Artwork(track.artworkUrl, track.name, Modifier.size(72.dp), corner = 14.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.recap_plays, summary.plays),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
                if (summary.minutes > 0) Text(stringResource(R.string.recap_minutes, summary.minutes),
                    style = MaterialTheme.typography.bodyMedium, color = InkDim)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LiquidButton(onClick = { if (month > first) month = month.minusMonths(1) }, backdrop = backdrop,
                isInteractive = month > first, flat = true, contentHeight = 32.dp) {
                Text(stringResource(R.string.recap_previous), color = if (month > first) Ink else InkDim)
            }
            if (month < current) LiquidButton(onClick = { month = month.plusMonths(1) }, backdrop = backdrop,
                flat = true, contentHeight = 32.dp) {
                Text(stringResource(R.string.recap_next), color = Ink)
            }
        }
        if (summary.tracks.isNotEmpty()) {
            Text(stringResource(R.string.recap_tracks), style = MaterialTheme.typography.titleMedium, color = Ink)
            summary.tracks.take(5).forEach { (track, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(track.artworkUrl, track.name, Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = InkDim)
                    }
                    Text(count.toString(), color = InkDim)
                }
            }
            Text(stringResource(R.string.recap_artists), style = MaterialTheme.typography.titleMedium, color = Ink)
            summary.artists.take(5).forEach { (artist, count) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(artist, modifier = Modifier.weight(1f), color = Ink)
                    Text(count.toString(), color = InkDim)
                }
            }
        }
        Text(stringResource(R.string.recap_partial), style = MaterialTheme.typography.bodySmall, color = InkDim)
    }
    }
}
