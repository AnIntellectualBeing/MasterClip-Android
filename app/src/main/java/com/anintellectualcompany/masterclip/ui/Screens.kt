package com.anintellectualcompany.masterclip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anintellectualcompany.masterclip.engine.CaptionPosition
import com.anintellectualcompany.masterclip.engine.CaptionStyle
import com.anintellectualcompany.masterclip.engine.EngineState
import com.anintellectualcompany.masterclip.engine.LiveMode
import com.anintellectualcompany.masterclip.engine.Search
import com.anintellectualcompany.masterclip.engine.LayoutChoice
import com.anintellectualcompany.masterclip.engine.etaText
import com.anintellectualcompany.masterclip.engine.findLink
import com.anintellectualcompany.masterclip.engine.parseClock
import java.io.File

/** What the person picked on the start screen. */
data class Choices(
    val url: String = "",
    val localUri: String? = null,
    val localName: String? = null,
    val count: Int = 5,
    val length: Int = 45,
    val layout: LayoutChoice = LayoutChoice.AUTO,
    val captions: Boolean = true,
    val from: String = "",
    val to: String = "",
    val captionStyle: CaptionStyle = CaptionStyle.POP,
    val captionPosition: CaptionPosition = CaptionPosition.AUTO,
    val liveMinutes: Int = 10,
    val liveMode: LiveMode = LiveMode.RECORD_NEXT,
    /** Set when a search result says this stream is live right now. */
    val liveHint: Boolean = false,
)

@Composable
fun HomeScreen(
    choices: Choices,
    onChange: (Choices) -> Unit,
    onPickVideo: () -> Unit,
    onSearch: () -> Unit,
    onMake: () -> Unit,
    recent: List<File>,
    onOpenClip: (File) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var showRange by remember { mutableStateOf(choices.from.isNotBlank() || choices.to.isNotBlank()) }
    val rangeOk = (choices.from.isBlank() || parseClock(choices.from) != null) && (choices.to.isBlank() || parseClock(choices.to) != null)
    val isLive = choices.localUri == null && (choices.liveHint || Search.looksLive(findLink(choices.url)))
    val hasSource = choices.localUri != null || findLink(choices.url) != null

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text("MasterClip", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)
            Text("Turn any video into Shorts, Reels and TikToks.", color = Ink.muted, fontSize = 15.sp)
        }

        Card {
            Label("1 · YOUR VIDEO")
            if (choices.localUri == null) {
                OutlinedTextField(
                    value = choices.url,
                    onValueChange = { onChange(choices.copy(url = it, liveHint = false)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste a YouTube or Twitch link") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        TextButton(onClick = {
                            val text = clipboard.getText()?.text
                            onChange(choices.copy(url = findLink(text) ?: text ?: "", liveHint = false))
                        }) { Text("Paste", color = Ink.brand, fontWeight = FontWeight.Bold) }
                    },
                )
                Center("or", size = 13)
                SoftButton("Search YouTube and Twitch streams", Modifier.fillMaxWidth(), onSearch)
                SoftButton("Pick a video from your phone", Modifier.fillMaxWidth(), onPickVideo)
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.soft).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎬  " + (choices.localName ?: "Video from your phone"), Modifier.weight(1f), color = Ink.text, maxLines = 1)
                    TextButton(onClick = { onChange(choices.copy(localUri = null, localName = null)) }) { Text("Change", color = Ink.brand) }
                }
            }
        }

        if (isLive) {
            Card {
                Label("LIVE STREAM")
                LiveControls(choices, onChange)
            }
        }

        Card {
            Label("2 · YOUR CLIPS")
            Text("How many", color = Ink.text, fontWeight = FontWeight.SemiBold)
            Pills(listOf(3, 5, 8, 10), choices.count, { "$it" }) { onChange(choices.copy(count = it)) }
            Text("How long", color = Ink.text, fontWeight = FontWeight.SemiBold)
            Pills(listOf(30, 45, 60, 90), choices.length, { "${it}s" }) { onChange(choices.copy(length = it)) }
            Text("Template", color = Ink.text, fontWeight = FontWeight.SemiBold)
            TemplatePicker(choices.layout) { onChange(choices.copy(layout = it)) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) { onChange(choices.copy(captions = !choices.captions)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Captions", color = Ink.text, fontWeight = FontWeight.SemiBold)
                    Text("Big word-by-word subtitles (YouTube links)", color = Ink.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = choices.captions,
                    onCheckedChange = { onChange(choices.copy(captions = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.brand),
                )
            }
            AnimatedVisibility(choices.captions) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Caption style", color = Ink.text, fontWeight = FontWeight.SemiBold)
                    CaptionStylePicker(choices.captionStyle) { onChange(choices.copy(captionStyle = it)) }
                    Text("Caption position", color = Ink.text, fontWeight = FontWeight.SemiBold)
                    PositionChips(choices.captionPosition) { onChange(choices.copy(captionPosition = it)) }
                }
            }
            TextButton(onClick = { showRange = !showRange }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showRange) "▾ Only part of the video" else "▸ Only part of the video (long streams)", color = Ink.brandDark)
            }
            AnimatedVisibility(showRange) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = choices.from, onValueChange = { onChange(choices.copy(from = it)) },
                            label = { Text("From") }, placeholder = { Text("1:20:00") }, singleLine = true,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            isError = choices.from.isNotBlank() && parseClock(choices.from) == null,
                        )
                        OutlinedTextField(
                            value = choices.to, onValueChange = { onChange(choices.copy(to = it)) },
                            label = { Text("To") }, placeholder = { Text("2:00:00") }, singleLine = true,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            isError = choices.to.isNotBlank() && parseClock(choices.to) == null,
                        )
                    }
                    Text("Write times like 1:20:00 or 45:30. Leave empty to use the whole video.", color = Ink.muted, fontSize = 12.sp)
                }
            }
        }

        BigButton("Make clips", enabled = hasSource && rangeOk, onClick = onMake)
        if (!hasSource) Center("Paste a link or pick a video to start", size = 13)

        if (recent.isNotEmpty()) {
            Gap(6)
            Label("YOUR CLIPS (${recent.size})")
            ClipGrid(recent.take(30), onOpenClip)
        }
        Gap(20)
    }
}

@Composable
fun ClipGrid(files: List<File>, onOpen: (File) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in files.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (f in row) ClipThumb(f, Modifier.weight(1f)) { onOpen(f) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun WorkingScreen(state: EngineState, onCancel: () -> Unit, onOpenClip: (File) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(10)
        Text("Making your clips", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)
        if (state.title.isNotBlank()) Text(state.title, color = Ink.muted, maxLines = 2)
        Card {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(state.progress * 100).toInt()}%", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Ink.brand)
                Spacer(Modifier.size(10.dp))
                Text(etaText(state.etaSec), color = Ink.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().heightIn(min = 10.dp).clip(RoundedCornerShape(6.dp)),
                color = Ink.brand,
                trackColor = Ink.soft,
                drawStopIndicator = {},
            )
            Text(state.step, color = Ink.muted)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.goodSoft).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Ink.good))
            Spacer(Modifier.size(10.dp))
            Text("You can lock your phone or use other apps. We'll tell you when it's done.", color = Ink.good, fontSize = 14.sp)
        }
        if (state.clips.isNotEmpty()) {
            Label("READY: ${state.clips.size} OF ${state.total}")
            ClipGrid(state.clips.map { File(it.path) }, onOpenClip)
        }
        Notes(state.notes)
        SoftButton("Stop", Modifier.fillMaxWidth(), onCancel)
    }
}

@Composable
fun DoneScreen(state: EngineState, onOpenClip: (File) -> Unit, onShareAll: () -> Unit, onNew: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(10)
        Box(Modifier.size(56.dp).clip(CircleShape).background(Ink.goodSoft), contentAlignment = Alignment.Center) {
            Text("✓", color = Ink.good, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text("${state.clips.size} clips ready", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)
        Text("Saved to your Gallery in Movies › MasterClip. Tap a clip to watch or share it.", color = Ink.muted)
        Notes(state.notes)
        ClipGrid(state.clips.map { File(it.path) }, onOpenClip)
        BigButton("Share all", onClick = onShareAll)
        SoftButton("Make more clips", Modifier.fillMaxWidth(), onNew)
        Gap(20)
    }
}

@Composable
fun FailedScreen(state: EngineState, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gap(10)
        Text("Couldn't make clips", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.badSoft).padding(16.dp)) {
            Text(state.error ?: "Something went wrong.", color = Ink.bad, fontSize = 16.sp)
        }
        Notes(state.notes.filter { it != state.error })
        BigButton("Try again", onClick = onRetry)
        SoftButton("Back", Modifier.fillMaxWidth(), onBack)
    }
}
