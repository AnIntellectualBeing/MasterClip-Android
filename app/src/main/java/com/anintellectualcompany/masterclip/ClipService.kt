package com.anintellectualcompany.masterclip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import com.anintellectualcompany.masterclip.engine.ClipEngine
import com.anintellectualcompany.masterclip.engine.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps clipping alive while the phone is locked or another app is open. */
@UnstableApi
class ClipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, WORK_ID, build("Getting ready", 0, true), type)
        if (!started) {
            started = true
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MasterClip:clip").apply { acquire(3 * 3600_000L) }
            scope.launch {
                ClipEngine.state.collect { s ->
                    val nm = getSystemService(NotificationManager::class.java)
                    when (s.phase) {
                        Phase.WORKING -> nm.notify(WORK_ID, build(s.step, (s.progress * 100).toInt(), false))
                        else -> {
                            val text = when (s.phase) {
                                Phase.DONE -> "${s.clips.size} clips ready. Saved to your Gallery."
                                Phase.FAILED -> s.error ?: "Couldn't make clips."
                                else -> null
                            }
                            ServiceCompat.stopForeground(this@ClipService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            if (text != null) nm.notify(DONE_ID, done(text))
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Making your clips")
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun done(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("MasterClip")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Clip progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL = "clips"
        private const val WORK_ID = 1
        private const val DONE_ID = 2
    }
}
