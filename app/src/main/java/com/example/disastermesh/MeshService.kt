package com.example.disastermesh

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import androidx.room.Room

class MeshService : Service() {
    private val CHANNEL_ID = "MeshServiceChannel"
    private lateinit var bleManager: BLEManager
    private lateinit var repository: MessageRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = BLEManager(this)
        
        val database = Room.databaseBuilder(
            applicationContext,
            MessageDatabase::class.java,
            "message_database"
        ).build()
        repository = MessageRepository(database.messageDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mesh Protocol Active")
            .setContentText("Scanning for nearby survivors and relaying SOS signals...")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        // Feature: Last-Gasp Resolution Broadcast (Swipe to Cancel)
        serviceScope.launch {
            val activeSos = repository.getActiveUserSos()
            if (activeSos != null && activeSos.status == "ACTIVE") {
                Log.d("MeshService", "App swiped! Broadcasting last-gasp RESOLVED for ${activeSos.messageId}")
                
                // 1. Mark as cancelled locally
                repository.updateMessageStatus(activeSos.messageId, "CANCELLED")
                
                // 2. Burst broadcast RESOLVED via BLE to clear mesh
                // We do this for 5 seconds before allowing the service to stop
                bleManager.startSosBroadcast(
                    sosId = activeSos.messageId,
                    lat = activeSos.latitude ?: 0.0,
                    lon = activeSos.longitude ?: 0.0,
                    triage = "RESOLVED",
                    vCount = activeSos.victimCount
                )
                
                delay(5000)
                bleManager.stopSosBroadcast()
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Disaster Mesh Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
}
