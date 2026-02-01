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
import wlproxy.Wlproxy
import wlproxy.TurnProxy
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class ProxyService : Service() {

    @Volatile private var proxy: TurnProxy? = null
    private val binder = LocalBinder()
    private val LOG_ACTION = "LOG_UPDATE"
    private val STATUS_ACTION = "STATUS_UPDATE"
    private val executor = Executors.newSingleThreadExecutor()

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

        Wlproxy.setLogger(object : wlproxy.Logger {
            override fun log(msg: String?) {
                msg?.let {
                    LocalBroadcastManager.getInstance(this@ProxyService).sendBroadcast(Intent(LOG_ACTION).putExtra("log", it.trim()))
                }
            }
        })

        executor.submit {
            try {
                proxy?.stop()
            } catch (e: Exception) {
                // Ignore stop errors
            }

            val newProxy = Wlproxy.newTurnProxy(peer, link, listen)
            proxy = newProxy
            
            thread {
                try {
                    newProxy.start()
                    sendStatus("Запущен")
                } catch (e: Exception) {
                    sendStatus("Ошибка: ${e.message}")
                    // Do not stopSelf() immediately as we might want to try again or keep service for logs
                }
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
