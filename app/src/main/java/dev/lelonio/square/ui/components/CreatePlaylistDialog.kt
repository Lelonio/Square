package dev.lelonio.square.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ImageSquare
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * A playlist's name, and where the source keeps them, a description, whether
 * others can find it, and a cover: for a new one, and for changing one.
 *
 * The same skin as [NameDialog], which renames one, so making a playlist and
 * renaming it look like the same kind of thing. It stays up while the service
 * answers and says so when it refuses; on success the caller opens the
 * playlist, which is the next thing anyone does with a new one.
 */
@Composable
fun CreatePlaylistDialog(
    title: String,
    confirmLabel: String,
    /** Description, visibility and cover; see MusicBackend.describesPlaylists. */
    describes: Boolean,
    busy: Boolean,
    error: String?,
    onCreate: (name: String, description: String, public: Boolean, cover: Uri?) -> Unit,
    onDismiss: () -> Unit,
    /** What an existing playlist has now; null for a new one. */
    initial: dev.lelonio.square.backend.PlaylistDetails? = null,
    /** Waiting for [initial]: the fields are not shown half-filled. */
    loading: Boolean = false,
) {
    var name by rememberSaveable(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var description by rememberSaveable(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var public by rememberSaveable(initial) { mutableStateOf(initial?.public ?: false) }
    var cover by rememberSaveable { mutableStateOf<Uri?>(null) }
    val focus = remember { FocusRequester() }
    // The system's photo picker: no permission to ask for, and only the one
    // picture it hands back is ever readable.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) cover = picked
    }
    val shape = RoundedCornerShape(24.dp)
    val create = { if (name.isNotBlank() && !busy) onCreate(name, description, public, cover) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .menuSkin(shape)
                .padding(22.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Ink, modifier = Modifier.size(22.dp))
                }
                return@Column
            }

            Row(
                Modifier.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (describes) {
                    Box(
                        Modifier
                            .size(COVER)
                            .clip(RoundedCornerShape(14.dp))
                            .background(FieldFill)
                            .clickable(enabled = !busy) {
                                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val shown: Any? = cover ?: initial?.artworkUrl
                        if (shown != null) {
                            AsyncImage(
                                model = shown,
                                contentDescription = stringResource(R.string.change_cover),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                PhosphorIcons.Regular.ImageSquare,
                                contentDescription = stringResource(R.string.choose_cover),
                                tint = InkDim,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = if (describes) 14.dp else 0.dp),
                ) {
                    Field(
                        value = name,
                        onValueChange = { name = it },
                        hint = stringResource(R.string.playlist_name),
                        singleLine = true,
                        imeAction = if (describes) ImeAction.Next else ImeAction.Done,
                        onDone = create,
                        modifier = Modifier.focusRequester(focus),
                    )
                    if (describes) {
                        Text(
                            stringResource(
                                if (cover == null && initial?.artworkUrl == null) R.string.choose_cover else R.string.change_cover,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !busy) {
                                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (describes) {
                Field(
                    value = description,
                    onValueChange = { description = it },
                    hint = stringResource(R.string.playlist_description),
                    singleLine = false,
                    imeAction = ImeAction.Default,
                    onDone = {},
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(min = 72.dp),
                )

                Row(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !busy) { public = !public }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.public_playlist), style = MaterialTheme.typography.bodyLarge, color = Ink)
                        Text(
                            stringResource(if (public) R.string.public_playlist_on else R.string.public_playlist_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                    Switch(on = public)
                }
            }

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Action(stringResource(R.string.cancel), if (busy) InkDim.copy(alpha = 0.5f) else InkDim) {
                    if (!busy) onDismiss()
                }
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = Ink,
                        modifier = Modifier
                            .padding(horizontal = 26.dp)
                            .size(18.dp),
                    )
                } else {
                    // Nothing to create while the name is empty: neither
                    // service takes a playlist called nothing.
                    Action(confirmLabel, if (name.isBlank()) InkDim else Ink, create)
                }
            }
        }
    }

    // The keyboard up at once for a new playlist: the name is what that sheet
    // is for. Not for an existing one, where the cover or the description is
    // as likely the reason it was opened.
    LaunchedEffect(loading) { if (!loading && initial == null) runCatching { focus.requestFocus() } }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    singleLine: Boolean,
    imeAction: ImeAction,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FieldFill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = InkDim)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
            cursorBrush = SolidColor(Ink),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = imeAction,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A plain switch in the dialog's ink: lit when on, a hairline track when off. */
@Composable
private fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) Ink else FieldFill, label = "switch track")
    val knobColor by animateColorAsState(if (on) Ink.copy(alpha = 0f) else InkDim, label = "switch knob")
    val offset by animateDpAsState(if (on) TRACK_W - KNOB - 4.dp else 4.dp, label = "switch offset")
    Box(
        Modifier
            .width(TRACK_W)
            .height(KNOB + 8.dp)
            .clip(CircleShape)
            .background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(KNOB)
                .clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.surface else knobColor),
        )
    }
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private val COVER: Dp = 84.dp
private val TRACK_W: Dp = 46.dp
private val KNOB: Dp = 20.dp
private val FieldFill = Color.White.copy(alpha = 0.08f)
