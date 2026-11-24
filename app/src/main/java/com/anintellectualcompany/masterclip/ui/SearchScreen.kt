package com.anintellectualcompany.masterclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.anintellectualcompany.masterclip.engine.FriendlyError
import com.anintellectualcompany.masterclip.engine.Search
import com.anintellectualcompany.masterclip.engine.SearchFilter
import com.anintellectualcompany.masterclip.engine.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Live = Color(0xFFE53935)

@Composable
fun SearchScreen(onBack: () -> Unit, onPick: (url: String, live: Boolean) -> Unit, initialQuery: String = "") {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun go(f: SearchFilter = filter) {
        if (query.isBlank()) return
        focus.clearFocus()
        job?.cancel()
        busy = true; error = null
        job = scope.launch {
            try {
                hits = withContext(Dispatchers.IO) { Search.run(context, query, f) }
            } catch (e: FriendlyError) {
                error = e.message; hits = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Search didn't work this time. Try again in a moment."; hits = null
            } finally {
                busy = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) go() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Back", color = Ink.brand, fontWeight = FontWeight.Bold) }
        }
        Text("Find a stream", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Streamer, game or topic") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = { TextButton(onClick = { go() }) { Text("Search", color = Ink.brand, fontWeight = FontWeight.Bold) } },
        )

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in SearchFilter.entries) {
                val on = f == filter
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (on) Ink.brand else Ink.soft)
                        .clickable(role = Role.RadioButton) { filter = f; if (query.isNotBlank()) go(f) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(f.label, color = if (on) Color.White else Ink.text, fontWeight = FontWeight.SemiBold) }
            }
        }

        val twitch = Search.twitchChannel(query)
        if (twitch != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEDE4FA))
                    .clickable { onPick(Search.twitchUrl(twitch), true) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("twitch.tv/$twitch (live)", fontWeight = FontWeight.Bold, color = Color(0xFF5B2FA3))
                    Text("Use this Twitch channel", fontSize = 12.sp, color = Ink.muted)
                }
                Text("›", fontSize = 22.sp, color = Color(0xFF5B2FA3))
            }
        }

        when {
            busy -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Ink.brand)
            }
            error != null -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.badSoft).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error ?: "", color = Ink.bad)
                SoftButton("Try again", onClick = { go() })
            }
            hits == null -> Center("Search YouTube for a streamer or a game. Try \"valorant\" or a channel name.", size = 14)
            hits.isNullOrEmpty() -> Center(
                if (filter == SearchFilter.LIVE) "Nobody is live for that right now. Try \"Past streams\"." else "Nothing found. Try different words.",
                size = 14,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (h in hits.orEmpty()) HitRow(h) { onPick(h.url, h.live) }
            }
        }
        Gap(20)
    }
}

@Composable
private fun HitRow(h: SearchHit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.card)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF221B17))) {
            AsyncImage(model = h.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (h.live) {
                Text(
                    "LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Live).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else if (h.durationSec != null) {
                Text(
                    Search.durationText(h.durationSec), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.weight(1f).heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(h.title, color = Ink.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(h.channel, color = Ink.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
