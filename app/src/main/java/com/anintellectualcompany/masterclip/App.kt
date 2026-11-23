package com.anintellectualcompany.masterclip

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.anintellectualcompany.masterclip.engine.Ytdl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application(), ImageLoaderFactory {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Unpack Python/ffmpeg off the main thread, then keep yt-dlp fresh (YouTube changes often).
        io.launch {
            runCatching { Ytdl.ensure(this@App) }
            Ytdl.updateIfDue(this@App)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
