package com.example.lannotes

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD

class NotesService : Service() {
    companion object { @Volatile var code: String? = null }
    private var server: NotesServer? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val c = i?.getStringExtra("code") ?: return START_NOT_STICKY
        code = c
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("srv", "PocketNotes", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "srv")
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("PocketNotes работает")
            .setContentText("Заметки доступны в вашей Wi-Fi сети")
            .setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 1, n, type)
        server?.stop()
        server = NotesServer(this, MainActivity.PORT, c).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        return START_NOT_STICKY
    }

    override fun onDestroy() { server?.stop(); code = null; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
