package com.anintellectualcompany.masterclip.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.anintellectualcompany.masterclip.engine.Campaign
import com.anintellectualcompany.masterclip.engine.Campaigns
import com.anintellectualcompany.masterclip.engine.Feed
import com.anintellectualcompany.masterclip.engine.Platform
import com.anintellectualcompany.masterclip.engine.Submission
import com.anintellectualcompany.masterclip.engine.SubmissionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun money(v: Double?) = if (v == null) "no rate" else "$" + String.format(java.util.Locale.US, "%.2f", v)

private fun openLink(context: android.content.Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { /* no browser installed */ }
}

@Composable
fun CampaignsScreen(onBack: () -> Unit, onClipFor: (Campaign) -> Unit, startOnMine: Boolean = false) {
    val context = LocalContext.current
    val store = remember { SubmissionStore(context) }
    var feed by remember { mutableStateOf<Feed?>(null) }
    var subs by remember { mutableStateOf(store.all()) }
    var mine by remember { mutableStateOf(startOnMine) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        feed = withContext(Dispatchers.IO) { Campaigns.loadFromNetwork(context) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Back", color = Ink.brand, fontWeight = FontWeight.Bold) }
        Text("Campaigns", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink.text)
        Pills(listOf(false, true), mine, { if (it) "My submissions" else "Campaigns" }) { mine = it }

        val f = feed
        if (f == null) {
            Center("Loading…")
        } else if (!mine) {
            if (f.campaigns.isEmpty()) {
                Card { Center("New campaigns are added here. Meanwhile, join a platform below.") }
            }
            f.campaigns.forEach { c -> CampaignCard(c, onOpen = { openLink(context, c.url) }, onClip = { onClipFor(c) }) }
            Label("PLATFORMS THAT PAY PER VIEWS")
            f.platforms.forEach { p -> PlatformCard(p) { openLink(context, p.url) } }
            val how = when (f.source) { "live" -> "Up to date."; "cache" -> "Offline. Showing the last list we saved."; else -> "Offline. Showing the list that came with the app." }
            Text((f.updated?.let { "List updated $it. " } ?: "") + how, color = Ink.muted, fontSize = 12.sp)
        } else {
            val total = Campaigns.total(subs, f.campaigns)
            Card {
                Label("ESTIMATED EARNINGS (ESTIMATE, NOT PAID MONEY)")
                Text(money(total), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Ink.brandDark)
            }
            if (adding) {
                AddSubmission(f.campaigns, onCancel = { adding = false }) { cid, name, url, views ->
                    store.add(cid, name, url, views); subs = store.all(); adding = false
                }
            }
            if (subs.isEmpty() && !adding) Card { Center("Nothing here yet. After you post a clip, add its link and type the views.") }
            subs.forEach { s ->
                SubmissionRow(s, f.campaigns.firstOrNull { it.id == s.campaignId },
                    onViews = { v -> store.setViews(s.id, v); subs = store.all() },
                    onOpen = { openLink(context, s.postedUrl) },
                    onDelete = { store.delete(s.id); subs = store.all() })
            }
            if (!adding) BigButton("Add a posted link") { adding = true }
            Text("Estimate = views / 1000 x the campaign's rate per 1K views. The platform's own count is the one that pays.", color = Ink.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CampaignCard(c: Campaign, onOpen: () -> Unit, onClip: () -> Unit) {
    Card {
        if (c.poster != null) {
            AsyncImage(model = c.poster, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)))
        }
        Text(c.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.text)
        c.brand?.let { Text(it, color = Ink.muted) }
        Text(if (c.payoutPer1k == null) "Rate on the campaign page" else "${money(c.payoutPer1k)} per 1K views",
            fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink.brandDark)
        val facts = buildList {
            c.budget?.let { add("Budget $it") }
            c.minViews?.let { add("From $it views") }
            if (c.clipLenMin != null && c.clipLenMax != null) add("${c.clipLenMin}-${c.clipLenMax} s clips")
            c.ends?.let { add("Ends $it") }
            addAll(c.platforms)
        }
        if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = Ink.muted, fontSize = 13.sp)
        c.rules.take(4).forEach { Text("• $it", color = Ink.muted, fontSize = 13.sp) }
        if (c.url != null) SoftButton("Open campaign page", Modifier.fillMaxWidth(), onOpen)
        BigButton("Clip for this", onClick = onClip)
    }
}

@Composable
private fun PlatformCard(p: Platform, onOpen: () -> Unit) {
    Card {
        Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink.text)
        Text(p.pays ?: "Check their page for rates.", color = if (p.pays == null) Ink.muted else Ink.text, fontSize = 14.sp)
        p.payoutMethod?.let { Text("Paid by $it", color = Ink.muted, fontSize = 13.sp) }
        p.minPayout?.let { Text("Minimum payout $it", color = Ink.muted, fontSize = 13.sp) }
        p.note?.let { Text(it, color = Ink.muted, fontSize = 13.sp) }
        SoftButton("Open site", Modifier.fillMaxWidth(), onOpen)
    }
}

@Composable
private fun SubmissionRow(s: Submission, c: Campaign?, onViews: (Long) -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    var text by remember(s.id, s.views) { mutableStateOf(s.views.toString()) }
    Card {
        Text(s.clipName.ifBlank { "Untitled clip" }, fontWeight = FontWeight.Bold, color = Ink.text)
        Text(listOfNotNull(c?.title, s.postedAt).joinToString(" · "), color = Ink.muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { t -> text = t.filter { it.isDigit() }.take(10); onViews(text.toLongOrNull() ?: 0L) },
                label = { Text("Views") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(money(Campaigns.estimate(s.views, c?.payoutPer1k)), fontWeight = FontWeight.ExtraBold, color = Ink.brandDark, fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.postedUrl.isNotBlank()) TextButton(onClick = onOpen) { Text("Open post", color = Ink.brand) }
            TextButton(onClick = onDelete) { Text("Remove", color = Ink.bad) }
        }
    }
}

@Composable
private fun AddSubmission(campaigns: List<Campaign>, onCancel: () -> Unit, onSave: (String, String, String, Long) -> Unit) {
    var campaignId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var views by remember { mutableStateOf("") }
    Card {
        Label("ADD A POSTED LINK")
        if (campaigns.isNotEmpty()) {
            val options = listOf("") + campaigns.map { it.id }
            Pills(options, campaignId, { id -> campaigns.firstOrNull { it.id == id }?.title ?: "No campaign" }) { campaignId = it }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Clip name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Link to your post") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(value = views, onValueChange = { views = it.filter { c -> c.isDigit() }.take(10) }, label = { Text("Views so far") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        BigButton("Save") { onSave(campaignId, name, url, views.toLongOrNull() ?: 0L) }
        SoftButton("Cancel", Modifier.fillMaxWidth(), onCancel)
    }
}

/** Banner on the start screen while clips are being made for a campaign. */
@Composable
fun CampaignBanner(title: String, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.brandSoft).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Campaign: $title", Modifier.weight(1f), color = Ink.brandDark, fontWeight = FontWeight.Bold, maxLines = 2)
        TextButton(onClick = onStop) { Text("Stop", color = Ink.brandDark) }
    }
}
