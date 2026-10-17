package com.lumilink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lumilink.R

/**
 * A foreground service whose only job is to keep the app process alive and "important" while
 * connected to the camera, so Android/HyperOS won't kill it (and drop the camera network) if the
 * user backgrounds the app during a long transfer.
 *
 * It does not hold the network itself — that's [com.lumilink.network.CameraNetworkManager]. This
 * just gives the process foreground priority and shows the ongoing notification.
 *
 * Kotlin/Android note: a bound service would implement `onBind`; this is a *started* service, so
 * `onBind` returns null and we drive it via [start]/[stop].
 */
class CameraConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY: if the system kills us, recreate the service when resources allow.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // A channel is required on API 26+ (we're minSdk 29, so always).
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera connection",
            NotificationManager.IMPORTANCE_LOW, // silent, no sound/vibration
        )
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Connected to camera")
            .setContentText("M43Link is holding the Wi-Fi link to your camera.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CameraConnectionService"
        private const val CHANNEL_ID = "camera_connection"
        private const val NOTIFICATION_ID = 1

        /** Start the service (best-effort — failure here must never break the connection). */
        fun start(context: Context) {
            try {
                val intent = Intent(context, CameraConnectionService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraConnectionService::class.java))
        }
    }
}
