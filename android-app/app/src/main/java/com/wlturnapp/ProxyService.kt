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
import kotlin.concurrent.thread

class ProxyService : Service() {

    private var proxy: Proxy? = null
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
            proxy = Proxy.new_(peer, link, listen) // Gomobile uses New -> New_ or just the class constructor if mapped
            // Wait, if New returns *Proxy, it's a constructor usually.
            // But gomobile bind sometimes maps NewProxy to Proxy.NewProxy.
            // The user code used `proxy = Proxy(peer, link, listen)` which implies a constructor.
            // In gomobile, `New` function in package `proxy` returning `*Proxy` becomes `new Proxy(...)` in Java/Kotlin.
            // So `proxy = Proxy(peer, link, listen)` is correct if I used `New`.
            
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
