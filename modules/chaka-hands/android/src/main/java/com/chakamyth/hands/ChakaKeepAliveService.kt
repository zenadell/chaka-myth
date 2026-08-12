package com.chakamyth.hands

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A short-lived foreground service Chaka starts while the screen operator is
 * running. Raising the process to foreground-service priority stops aggressive
 * OEM freezers (Samsung OneUI etc.) from suspending the JS thread while Chaka
 * is backgrounded and driving another app.
 */
class ChakaKeepAliveService : Service() {

  companion object {
    const val CHANNEL_ID = "chaka_keepalive"
    const val NOTIF_ID = 4477

    fun start(context: Context) {
      val intent = Intent(context, ChakaKeepAliveService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, ChakaKeepAliveService::class.java))
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    createChannel()
    val notification = Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("Chaka is working")
      .setContentText("Controlling your screen…")
      .setSmallIcon(android.R.drawable.ic_menu_manage)
      .setOngoing(true)
      .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIF_ID, notification)
    }
    return START_STICKY
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Chaka activity",
        NotificationManager.IMPORTANCE_LOW
      )
      channel.setShowBadge(false)
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }
}
