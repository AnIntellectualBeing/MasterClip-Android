package com.anintellectualcompany.masterclip

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.anintellectualcompany.masterclip.engine.ClipEngine
import com.anintellectualcompany.masterclip.engine.ClipRequest
import com.anintellectualcompany.masterclip.engine.Library
import com.anintellectualcompany.masterclip.engine.Phase
import com.anintellectualcompany.masterclip.engine.findLink
import com.anintellectualcompany.masterclip.engine.parseClock
import com.anintellectualcompany.masterclip.ui.Choices
import com.anintellectualcompany.masterclip.ui.DoneScreen
import com.anintellectualcompany.masterclip.ui.FailedScreen
import com.anintellectualcompany.masterclip.ui.HomeScreen
import com.anintellectualcompany.masterclip.ui.Ink
import com.anintellectualcompany.masterclip.ui.MasterClipTheme
import com.anintellectualcompany.masterclip.ui.PlayerDialog
import com.anintellectualcompany.masterclip.ui.SearchScreen
import com.anintellectualcompany.masterclip.ui.Settings
import com.anintellectualcompany.masterclip.ui.WorkingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<Choices?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readShare(intent)
        setContent { MasterClipTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    /** A link or video shared from another app lands straight in the start screen. */
    private fun readShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (type.startsWith("video/")) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
            shared.value = Choices(localUri = uri.toString(), localName = Library.displayName(this, uri))
        } else {
            val link = findLink(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
            shared.value = Choices(url = link)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val state by ClipEngine.state.collectAsStateWithLifecycle()
        var choices by remember { mutableStateOf(Settings.load(applicationContext)) }
        var searching by remember { mutableStateOf(false) }
        var open by remember { mutableStateOf<File?>(null) }
        var recent by remember { mutableStateOf(emptyList<File>()) }
        var refresh by remember { mutableStateOf(0) }

        val sharedNow by shared
        LaunchedEffect(sharedNow) {
            sharedNow?.let { s -> choices = choices.copy(url = s.url, localUri = s.localUri, localName = s.localName, liveHint = false); shared.value = null }
        }
        LaunchedEffect(state.phase, state.clips.size, refresh) {
            recent = withContext(Dispatchers.IO) { Library.recent(applicationContext) }
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) choices = choices.copy(localUri = uri.toString(), localName = Library.displayName(this, uri), url = "")
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        fun request() = ClipRequest(
            url = if (choices.localUri == null) findLink(choices.url) else null,
            localUri = choices.localUri,
            count = choices.count,
            lengthSec = choices.length,
            layout = choices.layout,
            captions = choices.captions,
            rangeStart = parseClock(choices.from),
            rangeEnd = parseClock(choices.to),
            captionStyle = choices.captionStyle,
            captionPosition = choices.captionPosition,
            liveMinutes = choices.liveMinutes,
            liveMode = choices.liveMode,
        )

        fun make() {
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            Settings.save(applicationContext, choices)
            ClipEngine.start(this, request())
        }

        Box(Modifier.fillMaxSize().background(Ink.bg), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                when (state.phase) {
                    Phase.IDLE -> if (searching) {
                        androidx.activity.compose.BackHandler { searching = false }
                        SearchScreen(
                            onBack = { searching = false },
                            onPick = { url, live ->
                                choices = choices.copy(url = url, localUri = null, localName = null, liveHint = live)
                                searching = false
                            },
                        )
                    } else HomeScreen(
                        choices = choices,
                        onChange = { choices = it; Settings.save(applicationContext, it) },
                        onSearch = { searching = true },
                        onPickVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        onMake = ::make,
                        recent = recent,
                        onOpenClip = { open = it },
                    )
                    Phase.WORKING -> WorkingScreen(state, onCancel = ClipEngine::cancel, onOpenClip = { open = it })
                    Phase.DONE -> DoneScreen(
                        state,
                        onOpenClip = { open = it },
                        onShareAll = { Library.share(this@MainActivity, state.clips.map { File(it.path) }.filter { it.exists() }) },
                        onNew = { ClipEngine.reset() },
                    )
                    Phase.FAILED -> FailedScreen(state, onRetry = ::make, onBack = { ClipEngine.reset() })
                }
            }
        }

        open?.let { f ->
            PlayerDialog(
                file = f,
                onClose = { open = null },
                onDelete = { Library.delete(f); open = null; refresh++ },
            )
        }
    }
}
