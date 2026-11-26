package com.example.lannotes

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class NotesService : Service() {
    private var server: NotesServer? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val code = i?.getStringExtra("code") ?: return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("srv", "Сервер заметок", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "srv")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Сервер заметок работает")
            .setContentText("Заметки доступны в вашей Wi-Fi сети")
            .setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 1, n, type)
        server?.stop()
        server = NotesServer(this, MainActivity.PORT, code).also { it.start(0, false) }
        return START_NOT_STICKY
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
