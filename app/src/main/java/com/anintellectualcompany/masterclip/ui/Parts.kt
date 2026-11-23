package com.anintellectualcompany.masterclip.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.anintellectualcompany.masterclip.engine.Library
import java.io.File

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.card)
            .border(1.dp, Ink.line, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun Label(text: String) = Text(text, color = Ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

/** Row of pill buttons, one selected. */
@Composable
fun <T> Pills(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) Ink.brand else Ink.soft)
                    .clickable(role = Role.RadioButton) { onPick(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), color = if (on) Color.White else Ink.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink.brand, disabledContainerColor = Ink.line),
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text, color = Ink.text, fontWeight = FontWeight.SemiBold) }
}

/** 9:16 thumbnail of a clip with a play mark. */
@Composable
fun ClipThumb(file: File, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF221B17))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(model = file, contentDescription = "Clip ${file.nameWithoutExtension}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = Color.White, fontSize = 18.sp) }
    }
}

/** Full-screen player for one clip, with Share and Delete. */
@Composable
fun PlayerDialog(file: File, onClose: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { Library.share(context, listOf(file)) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.brand),
                ) { Text("Share", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("Delete", color = Color.White)
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun Notes(notes: List<String>) {
    if (notes.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.brandSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (n in notes.distinct()) Text("• $n", color = Ink.brandDark, fontSize = 14.sp)
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun Center(text: String, size: Int = 15, color: Color = Ink.muted, weight: FontWeight = FontWeight.Normal) =
    Text(text, color = color, fontSize = size.sp, fontWeight = weight, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
