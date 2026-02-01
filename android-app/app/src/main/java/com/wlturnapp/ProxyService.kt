package com.wlturnapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import go.Seq
import proxy.Proxy
import proxy.TurnProxy
import kotlin.concurrent.thread

class ProxyService : Service() {

    private var proxy: TurnProxy? = null
    private val binder = LocalBinder()
    private val LOG_ACTION = "LOG_UPDATE"
    private val STATUS_ACTION = "STATUS_UPDATE"

    inner class LocalBinder : Binder() {
        fun getService() = this@ProxyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Seq.setContext(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = intent?.getStringExtra("peer") ?: return START_NOT_STICKY
        val link = intent?.getStringExtra("link") ?: return START_NOT_STICKY
        val listen = intent?.getStringExtra("listen") ?: "127.0.0.1:9000"

        val notification = NotificationCompat.Builder(this, "proxy_channel")
            .setContentTitle("WLTurnApp")
            .setContentText("Прокси запущен")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)

        Proxy.setLogCallback { msg ->
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(LOG_ACTION).putExtra("log", msg.trim()))
        }

        thread {
            proxy = Proxy.newTurnProxy(peer, link, listen)
            try {
                proxy?.start()
                sendStatus("Запущен")
            } catch (e: Exception) {
                sendStatus("Ошибка: ${e.message}")
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun sendStatus(status: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(STATUS_ACTION).putExtra("status", status))
    }

    override fun onDestroy() {
        proxy?.stop()
        sendStatus("Остановлен")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("proxy_channel", "WLTurn Proxy", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
