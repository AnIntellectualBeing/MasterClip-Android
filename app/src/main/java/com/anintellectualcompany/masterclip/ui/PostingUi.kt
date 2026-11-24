package com.anintellectualcompany.masterclip.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anintellectualcompany.masterclip.engine.PostText
import com.anintellectualcompany.masterclip.engine.PostingStore
import com.anintellectualcompany.masterclip.engine.Zernio
import com.anintellectualcompany.masterclip.engine.ZernioAccount
import com.anintellectualcompany.masterclip.engine.ZernioError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun platformName(p: String) = if (p == "youtube") "YouTube" else "Instagram"

/** Settings: your Zernio key, which accounts to post to, and auto-posting. */
@Composable
fun PostingScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(PostingStore.key(context) != null) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<ZernioAccount>() }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(PostingStore.selected(context).map { it.id }) } }
    var known by remember { mutableStateOf(PostingStore.selected(context)) }
    var tags by remember { mutableStateOf(PostingStore.hashtags(context)) }
    var auto by remember { mutableStateOf(PostingStore.autoPost(context)) }
    var gap by remember { mutableStateOf(PostingStore.gapMinutes(context)) }

    fun saveChosen() {
        val all = (found + known).distinctBy { it.id }
        PostingStore.setSelected(context, all.filter { it.id in chosen })
        known = PostingStore.selected(context)
    }

    fun check() {
        val typed = key.trim()
        val use = typed.ifBlank { PostingStore.key(context).orEmpty() }
        if (use.isBlank()) { message = "Paste your Zernio key first."; isError = true; return }
        busy = true; message = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Zernio(use).accounts() }
                if (typed.isNotBlank()) { PostingStore.setKey(context, typed); hasKey = true; key = "" }
                found.clear(); found.addAll(list)
                if (list.isEmpty()) {
                    message = "The key works, but no Instagram or YouTube account is connected in Zernio yet."
                    isError = true
                } else {
                    // Nothing picked yet: pick them all.
                    if (chosen.isEmpty()) chosen.addAll(list.map { it.id })
                    saveChosen()
                    message = "Key saved. ${list.size} account${if (list.size == 1) "" else "s"} found."
                    isError = false
                }
            } catch (e: ZernioError) {
                message = e.message; isError = true
            } catch (e: Exception) {
                message = "Couldn't check the key. Check your internet and try again."; isError = true
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("< Back", color = Ink.brandDark, fontWeight = FontWeight.SemiBold) }
        Text("Posting", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)
        Text("Post your clips to Instagram and YouTube with Zernio.", color = Ink.muted, fontSize = 15.sp)

        Card {
            Label("1 · YOUR ZERNIO KEY")
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (hasKey) "Key saved. Paste a new one to change it." else "Paste your key from zernio.com") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show", color = Ink.brand) } },
            )
            Text("It is kept encrypted on this phone and only sent to Zernio.", color = Ink.muted, fontSize = 12.sp)
            BigButton(if (busy) "Checking..." else "Check", enabled = !busy, onClick = ::check)
            message?.let {
                Text(it, color = if (isError) Ink.bad else Ink.good, fontSize = 14.sp)
            }
            if (hasKey) TextButton(onClick = {
                PostingStore.setKey(context, null); PostingStore.setSelected(context, emptyList())
                hasKey = false; found.clear(); chosen.clear(); known = emptyList(); message = "Key removed."; isError = false
            }) { Text("Remove my key", color = Ink.bad) }
        }

        val shown = (found + known).distinctBy { it.id }
        if (shown.isNotEmpty()) Card {
            Label("2 · POST TO")
            for (a in shown) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        if (a.id in chosen) chosen.remove(a.id) else chosen.add(a.id)
                        saveChosen()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = a.id in chosen,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Ink.brand),
                    )
                    Text("${platformName(a.platform)}  ${a.name}", color = Ink.text, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }

        Card {
            Label("OPTIONS")
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it; PostingStore.setHashtags(context, it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(PostText.DEFAULT_TAGS) },
                shape = RoundedCornerShape(14.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-post every clip", color = Ink.text, fontWeight = FontWeight.SemiBold)
                    Text("Each new clip is posted for you, one after the other, even if the app is closed.", color = Ink.muted, fontSize = 12.sp)
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { auto = it; PostingStore.setAutoPost(context, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.brand),
                )
            }
            if (auto) {
                Text("Gap between posts", color = Ink.text, fontWeight = FontWeight.SemiBold)
                Pills(listOf(30, 60, 120, 240), gap, { if (it < 60) "$it min" else "${it / 60} h" }) { gap = it; PostingStore.setGapMinutes(context, it) }
                if (!PostingStore.ready(context)) Text("Add your key and pick an account first.", color = Ink.bad, fontSize = 13.sp)
            }
            Text("Limits: Instagram up to 10 posts a day, YouTube up to 50 a day per account.", color = Ink.muted, fontSize = 12.sp)
        }
        Gap(20)
    }
}

/** "Post" on one finished clip. */
@Composable
fun PostDialog(file: File, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val accounts = remember { PostingStore.selected(context) }
    val hasKey = remember { PostingStore.key(context) != null }
    var caption by remember { mutableStateOf(PostText.caption(PostText.titleFromFile(file.name), PostingStore.hashtags(context))) }
    var scheduled by remember { mutableStateOf<LocalDateTime?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM, h:mm a") }

    val act = LocalContext.current
    fun pickTime() {
        val now = LocalDateTime.now().plusMinutes(15)
        DatePickerDialog(act, { _, y, m, d ->
            TimePickerDialog(act, { _, h, min -> scheduled = LocalDateTime.of(y, m + 1, d, h, min) }, now.hour, now.minute, false).show()
        }, now.year, now.monthValue - 1, now.dayOfMonth).show()
    }

    fun send() {
        val key = PostingStore.key(context)
        if (key == null || accounts.isEmpty()) return
        busy = true; failed = false; result = null; progress = 0f
        val at = scheduled
        scope.launch {
            try {
                val zone = ZoneId.systemDefault()
                val url = withContext(Dispatchers.IO) {
                    val z = Zernio(key)
                    z.upload(file) { progress = it }
                }
                withContext(Dispatchers.IO) {
                    val whenLocal = at?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                    Zernio(key).post(url, caption, accounts, whenLocal, zone.id)
                }
                done = true
                result = if (at != null) "Scheduled for ${at.format(fmt)}. Zernio will post it for you." else "Posted. It can take a minute to show up."
            } catch (e: ZernioError) {
                failed = true; result = e.message
            } catch (e: Exception) {
                failed = true; result = "Couldn't post. Check your internet and try again."
            }
            busy = false
        }
    }

    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Ink.bg).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Post this clip", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)
            if (!hasKey || accounts.isEmpty()) {
                Text(
                    if (!hasKey) "Add your Zernio key first." else "Pick an Instagram or YouTube account first.",
                    color = Ink.muted,
                )
                BigButton("Open Posting settings", onClick = { onClose(); onOpenSettings() })
                SoftButton("Close", Modifier.fillMaxWidth(), onClose)
            } else {
                Card {
                    Label("POSTING TO")
                    Text(accounts.joinToString("  ·  ") { "${platformName(it.platform)} ${it.name}" }, color = Ink.text)
                }
                Card {
                    Label("CAPTION (FIRST LINE IS THE YOUTUBE TITLE)")
                    OutlinedTextField(
                        value = caption, onValueChange = { caption = it }, enabled = !busy && !done,
                        modifier = Modifier.fillMaxWidth(), minLines = 4, shape = RoundedCornerShape(14.dp),
                    )
                    Label("WHEN")
                    Pills(listOf(false, true), scheduled != null, { if (it) "Schedule" else "Post now" }) { later ->
                        if (later) pickTime() else scheduled = null
                    }
                    scheduled?.let {
                        Text(
                            it.format(fmt), color = Ink.brandDark, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { pickTime() }.padding(vertical = 8.dp),
                        )
                    }
                    Text("Limits: Instagram up to 10 posts a day, YouTube up to 50 a day per account.", color = Ink.muted, fontSize = 12.sp)
                }
                if (busy) Text(if (progress < 0.99f) "Uploading the video... ${(progress * 100).toInt()}%" else "Sending to Zernio...", color = Ink.muted)
                result?.let {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (failed) Ink.badSoft else Ink.goodSoft).padding(14.dp)) {
                        Text(it, color = if (failed) Ink.bad else Ink.good, fontSize = 15.sp)
                    }
                }
                if (!done) BigButton(
                    if (busy) "Please wait..." else if (scheduled != null) "Schedule post" else "Post now",
                    enabled = !busy && caption.isNotBlank(), onClick = ::send,
                )
                SoftButton(if (done) "Done" else "Close", Modifier.fillMaxWidth().heightIn(min = 52.dp)) { if (!busy) onClose() }
            }
        }
    }
}
