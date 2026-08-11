package com.appx.syncmedia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SyncForegroundService : Service(), FileSyncer.SyncProgressListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileSyncer = FileSyncer()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val sourceUriString = intent?.getStringExtra(EXTRA_SOURCE_URI)
        val destUriString = intent?.getStringExtra(EXTRA_DEST_URI)

        if (sourceUriString.isNullOrEmpty() || destUriString.isNullOrEmpty()) {
            notifyFailure("Invalid source or destination folder")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground(buildProgressNotification(0))

        serviceScope.launch {
            try {
                val sourceUri = Uri.parse(sourceUriString)
                val destUri = Uri.parse(destUriString)
                val sourceDir = DocumentFile.fromTreeUri(this@SyncForegroundService, sourceUri)
                val destDir = DocumentFile.fromTreeUri(this@SyncForegroundService, destUri)

                if (sourceDir == null || destDir == null) {
                    notifyFailure("Cannot access selected folders")
                    stopSelf()
                    return@launch
                }

                fileSyncer.sync(this@SyncForegroundService, sourceDir, destDir, this@SyncForegroundService)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                notifyFailure(e.message ?: "Unknown sync error")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onProgressUpdate(progress: Int) {
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_PROGRESS_ID,
            buildProgressNotification(progress)
        )
    }

    override fun onSyncComplete() {
        val completeNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Sync complete")
            .setContentText("All files have been synchronized")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(createMainActivityPendingIntent())
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_COMPLETE_ID, completeNotification)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_PROGRESS_ID, notification, ServiceInfoCompat.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_PROGRESS_ID, notification)
        }
    }

    private fun buildProgressNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sync in progress")
            .setContentText("Synchronizing files... $progress%")
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createMainActivityPendingIntent())
            .build()
    }

    private fun notifyFailure(message: String) {
        val failedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Sync failed")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createMainActivityPendingIntent())
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_COMPLETE_ID, failedNotification)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createMainActivityPendingIntent(): PendingIntent {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows file synchronization progress and results"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SyncForegroundService"
        private const val CHANNEL_ID = "sync_status_channel"
        private const val NOTIFICATION_PROGRESS_ID = 1001
        private const val NOTIFICATION_COMPLETE_ID = 1002

        private const val EXTRA_SOURCE_URI = "extra_source_uri"
        private const val EXTRA_DEST_URI = "extra_dest_uri"

        fun createStartIntent(context: Context, sourceUri: Uri, destUri: Uri): Intent {
            return Intent(context, SyncForegroundService::class.java).apply {
                putExtra(EXTRA_SOURCE_URI, sourceUri.toString())
                putExtra(EXTRA_DEST_URI, destUri.toString())
            }
        }
    }
}

private object ServiceInfoCompat {
    const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 0x00000001
}
