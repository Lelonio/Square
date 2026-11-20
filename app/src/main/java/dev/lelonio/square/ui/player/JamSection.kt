package dev.lelonio.square.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.LinkSimple
import com.adamglin.phosphoricons.regular.QrCode
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.SignOut
import com.adamglin.phosphoricons.regular.Users
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.R
import dev.lelonio.square.data.Jam
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Jam, under the devices it plays on: started from here with Soloist as
 * the speaker everyone plays to, joined from someone's link, and who is in it.
 * Kept fresh while it is on screen; see Jam.
 */
@Composable
fun JamSection() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val session by Jam.session.collectAsStateWithLifecycle()
    val busy by Jam.busy.collectAsStateWithLifecycle()
    val error by Jam.error.collectAsStateWithLifecycle()
    val soloist by dev.lelonio.square.playback.soloist.SoloistEngine.enabled(context).collectAsStateWithLifecycle()

    // Who is in it changes from other phones: read again every few seconds
    // while the section is showing.
    LaunchedEffect(Unit) {
        while (true) {
            Jam.refresh()
            delay(5_000)
        }
    }

    Column(Modifier.padding(top = 10.dp, bottom = 18.dp)) {
        Text(
            stringResource(R.string.jam_title),
            style = MaterialTheme.typography.titleLarge,
            color = GlassInk,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        )

        val now = session
        when {
            now != null -> JamNow(
                session = now,
                busy = busy,
                onShare = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, now.link)
                    context.startActivity(
                        android.content.Intent.createChooser(send, context.getString(R.string.jam_share))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onCopy = { clipboard.setText(AnnotatedString(now.link)) },
                onClose = { scope.launch { if (now.ownedByMe) Jam.end() else Jam.leave() } },
            )

            soloist != true -> Note(stringResource(R.string.jam_needs_soloist))

            else -> JamStart(
                busy = busy,
                fromClipboard = remember { Jam.tokenIn(clipboard.getText()?.text) },
                onStart = { scope.launch { Jam.start() } },
                onJoin = { token -> scope.launch { Jam.join(token) } },
            )
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun JamStart(
    busy: Boolean,
    fromClipboard: String?,
    onStart: () -> Unit,
    onJoin: (String) -> Unit,
) {
    val context = LocalContext.current
    Note(stringResource(R.string.jam_start_note))
    ActionRow(PhosphorIcons.Regular.Users, stringResource(R.string.jam_start), busy, onStart)
    // Someone's code, scanned with Google's scanner (Play services' own screen,
    // so no camera permission here).
    ActionRow(PhosphorIcons.Regular.QrCode, stringResource(R.string.jam_scan), busy) {
        runCatching {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context)
                .startScan()
                .addOnSuccessListener { code -> Jam.tokenIn(code.rawValue)?.let(onJoin) }
        }
    }
    // A Jam link already copied, offered as it is: the way in that needs no
    // setting changed to have Square open Spotify's links.
    if (fromClipboard != null) {
        ActionRow(PhosphorIcons.Regular.LinkSimple, stringResource(R.string.jam_join_clipboard), busy) {
            onJoin(fromClipboard)
        }
    }
}

@Composable
private fun JamNow(
    session: Jam.Session,
    busy: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    session.hostDevice?.let {
        Note(stringResource(R.string.jam_on, it))
    }
    // The way in for whoever is next to you: a code to point a camera at.
    if (session.ownedByMe) {
        val qr = remember(session.link) { qrOf(session.link) }
        if (qr != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = qr,
                    contentDescription = session.link,
                    modifier = Modifier
                        .size(168.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(10.dp),
                )
            }
        }
        ActionRow(PhosphorIcons.Regular.ShareNetwork, stringResource(R.string.jam_share), false, onShare)
        ActionRow(PhosphorIcons.Regular.Copy, stringResource(R.string.jam_copy), false, onCopy)
    }

    session.members.forEach { member ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = member.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(GlassInkDim.copy(alpha = 0.3f)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    member.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = GlassInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val role = listOfNotNull(
                    stringResource(R.string.jam_host).takeIf { member.owner },
                    stringResource(R.string.jam_you).takeIf { member.me },
                ).joinToString(" · ")
                if (role.isNotEmpty()) {
                    Text(role, style = MaterialTheme.typography.bodySmall, color = GlassInkDim)
                }
            }
        }
    }

    ActionRow(
        if (session.ownedByMe) PhosphorIcons.Regular.X else PhosphorIcons.Regular.SignOut,
        stringResource(if (session.ownedByMe) R.string.jam_end else R.string.jam_leave),
        busy,
        onClose,
    )
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = GlassInk, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = GlassInk,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(color = GlassInkDim, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = GlassInkDim,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
    )
}

/**
 * Who is in a Jam, as their pictures overlapping, the way Spotify shows it:
 * up to [max], a letter where there is no picture.
 */
@Composable
fun JamAvatars(members: List<Jam.Member>, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, max: Int = 4) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-(size * 0.35f))) {
        members.take(max).forEach { member ->
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0xFF4A90E2)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    member.name.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                if (member.imageUrl != null) {
                    AsyncImage(
                        model = member.imageUrl,
                        contentDescription = member.name,
                        modifier = Modifier.size(size).clip(CircleShape),
                    )
                }
            }
        }
    }
}

/** "Jam di …": the Jam's owner by name, for the lines that say one is on. */
@Composable
fun jamTitle(session: Jam.Session): String =
    stringResource(R.string.jam_of, session.members.firstOrNull { it.owner }?.name ?: session.members.firstOrNull()?.name.orEmpty())

/** [text] as a QR code, black on white. */
private fun qrOf(text: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
    val bitmap = android.graphics.Bitmap.createBitmap(matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bitmap.asImageBitmap()
}.getOrNull()
