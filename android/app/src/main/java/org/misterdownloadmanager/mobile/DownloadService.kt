package org.misterdownloadmanager.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: kotlinx.coroutines.Job? = null
    private var lock: PowerManager.WakeLock? = null
    private val manager get() = getSystemService(NotificationManager::class.java)
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("downloads", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:downloads")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(101, notification("…"))
        val id = intent?.getLongExtra("id", -1L) ?: -1L
        scope.launch {
            try {
                Engine.initialize(this@DownloadService)
                if (id >= 0) Engine.call("start", "id" to id)
            } catch (e: Exception) {
                manager.notify(101, notification(e.message ?: "Engine error"))
            }
            synchronized(this@DownloadService) {
                if (poll?.isActive != true) poll = scope.launch { monitor() }
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun monitor() {
        var idle = 0
        while (currentCoroutineContext().isActive) {
            try {
                val jobs = Engine.list().filter { it.running }
                if (jobs.isEmpty()) {
                    if (lock?.isHeld == true) lock?.release()
                    if (++idle >= 5) { stopSelf(); return }
                } else {
                    idle = 0
                    if (lock?.isHeld != true) lock?.acquire(10 * 60 * 1000L)
                    manager.notify(101, notification("${jobs.size} • ${formatBytes(jobs.sumOf { it.speed })}/s"))
                }
            } catch (e: Exception) { manager.notify(101, notification(e.message ?: "Engine error")) }
            delay(1000)
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        // API 35: data-sync time budget exhausted; request a safe pause before stopping.
        scope.launch {
            runCatching { Engine.list().filter { it.status == "running" }.forEach { Engine.call("pause", "id" to it.id) } }
            stopSelf(startId)
        }
    }
    override fun onDestroy() {
        scope.cancel()
        if (lock?.isHeld == true) lock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
