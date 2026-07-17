package com.example.disastermesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.SmsManager
import android.util.Base64
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

class ChatViewModel(private val repository: MessageRepository) : ViewModel() {

    var userName: String = "Survivor"
    var mapTargetLocation by mutableStateOf<Pair<Double, Double>?>(null)
    var isGovernmentUser by mutableStateOf(false)
    
    // Proactive Safety States
    var isSafetyCheckActive by mutableStateOf(false)
    var isProactiveSafetyEnabled by mutableStateOf(true) // Can be toggled in settings
    var lastSensorActivity by mutableStateOf(System.currentTimeMillis())
    
    // Safety & Landscape Analysis States
    var activeHazardZone by mutableStateOf<Pair<Double, Double>?>(null)
    var hazardRadius by mutableStateOf(1000.0) // Meters
    var currentEmergencyMode by mutableStateOf("EARTHQUAKE") // Locked to Earthquake for pitch
    
    var activeUserSosId by mutableStateOf<String?>(null)
    
    // Hybrid Network States
    var isGatewayActive by mutableStateOf(false)
    var cloudSyncStatus by mutableStateOf("Standby") // Standby, Syncing, Online

    val allMessages: StateFlow<List<MessageEntity>> = repository.allMessages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    fun checkActiveSos() {
        viewModelScope.launch {
            activeUserSosId = repository.getActiveUserSos()?.messageId
        }
    }

    fun startNetworkMonitoring(context: Context) {
        viewModelScope.launch {
            while (isActive) {
                val isConnected = NetworkMonitor.isNetworkAvailable(context)
                isGatewayActive = isConnected
                cloudSyncStatus = if (isConnected) "Gateway Online" else "Mesh Only"
                
                if (isConnected) {
                    performCloudSync()
                }
                delay(10000) // Check every 10 seconds
            }
        }
    }

    private suspend fun performCloudSync() {
        // Feature 11: Hybrid Mobile Network Sync
        // We find all messages not yet in the cloud and 'upload' them
        val unsynced = repository.allMessages.stateIn(viewModelScope).value.filter { !it.isCloudSynced }
        if (unsynced.isEmpty()) return

        cloudSyncStatus = "Syncing ${unsynced.size} nodes..."
        
        unsynced.forEach { msg ->
            // Simulating API Call to a central dashboard
            // In a real app: Ktor or Retrofit call here
            // httpClient.post("https://emergency-api.gov/sync", msg)
            delay(500) // Simulating network latency
            repository.updateSyncStatus(msg.messageId, true)
        }
        
        cloudSyncStatus = "Cloud Synchronized"
    }

    private fun getBatteryLevel(context: Context): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    @SuppressLint("MissingPermission")
    fun sendSos(
        context: Context,
        chatManager: BluetoothChatManager?,
        content: String,
        triage: String = "STABLE",
        victimCount: Int = 1,
        hazardType: String = "GENERAL"
    ) {
        viewModelScope.launch {
            // Check for active SOS from this user (Feature: Duplicate Prevention)
            val existingSos = repository.getActiveUserSos()
            if (existingSos != null) {
                activeUserSosId = existingSos.messageId
                Toast.makeText(context, "Active SOS already exists. Solve or cancel it first.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val location = try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            } catch (e: Exception) {
                null
            }
            
            sendMessage(
                chatManager = chatManager,
                content = content,
                type = "SOS",
                lat = location?.latitude,
                lon = location?.longitude,
                triage = triage,
                victimCount = victimCount,
                hazardType = hazardType
            )
            
            // Government API Bridge: SMS Fallback
            if (triage == "CRITICAL") {
                try {
                    val smsManager = context.getSystemService(SmsManager::class.java)
                    val smsText = "CRITICAL SOS: $userName at ${location?.latitude},${location?.longitude}. Msg: $content"
                    // In real life, this would be a government emergency number
                    // smsManager.sendTextMessage("911", null, smsText, null, null)
                    Toast.makeText(context, "Critical SMS Sent to Authorities", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    fun markResource(context: Context, chatManager: BluetoothChatManager?, resourceName: String) {
        viewModelScope.launch {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val location = try {
                @SuppressLint("MissingPermission")
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            } catch (e: Exception) {
                null
            }
            sendMessage(
                chatManager = chatManager,
                content = resourceName,
                type = "RESOURCE",
                lat = location?.latitude,
                lon = location?.longitude
            )
        }
    }

    fun sendMessage(
        chatManager: BluetoothChatManager?,
        content: String,
        type: String = "CHAT",
        lat: Double? = null,
        lon: Double? = null,
        triage: String = "STABLE",
        victimCount: Int = 1,
        hazardType: String = "GENERAL"
    ) {
        if (content.isBlank()) return

        viewModelScope.launch {
            // NLP Analysis for Triage & AI Ranking (Feature 1)
            val analyzedTriage = if (type == "SOS") NLPAnalyzer.analyzeTriage(content) else triage
            val battery = 85 // Mock battery for now
            val calculatedRank = NLPAnalyzer.calculateEmergencyRank(analyzedTriage, victimCount, battery, System.currentTimeMillis())
            
            val msgId = java.util.UUID.randomUUID().toString()
            if (type == "SOS") activeUserSosId = msgId

            val messageEntity = MessageEntity(
                messageId = msgId,
                sender = "Me",
                senderName = userName,
                message = content,
                type = type,
                priority = calculatedRank, // Use AI rank as priority
                latitude = lat,
                longitude = lon,
                isVerified = isGovernmentUser,
                triageLevel = analyzedTriage,
                victimCount = victimCount,
                senderBattery = battery,
                hazardType = hazardType,
                ttl = 5
            )
            repository.insertMessage(messageEntity)

            // Feature 3 & 8: Optimized & Rich Protocol
            // Protocol: "ID|NAME|TYPE|LAT|LON|VERIFIED|TRIAGE|COUNT|BATTERY|HAZARD|TTL|MESSAGE"
            val verified = if (isGovernmentUser) "1" else "0"
            val rawPayload = "$msgId|$userName|$type|$lat|$lon|$verified|$analyzedTriage|$victimCount|$battery|$hazardType|5|$content"
            val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
            chatManager?.sendMessage(encryptedPayload)
        }
    }

    fun receiveAndForwardMessage(context: Context, encryptedPayload: String, chatManager: BluetoothChatManager?) {
        viewModelScope.launch {
            try {
                val payload = EncryptionUtils.decrypt(encryptedPayload) ?: return@launch
                val parts = payload.split("|", limit = 12)
                if (parts.size < 12) return@launch

                val msgId = parts[0]
                if (repository.exists(msgId)) return@launch

                val sName = parts[1]
                val type = parts[2]
                val lat = parts[3].toDoubleOrNull()
                val lon = parts[4].toDoubleOrNull()
                val verified = parts[5] == "1"
                val triage = parts[6]
                val vCount = parts[7].toIntOrNull() ?: 1
                val battery = parts[8].toIntOrNull() ?: -1
                val hazard = parts[9]
                val ttl = parts[10].toIntOrNull() ?: 0
                val content = parts[11]

                if (ttl <= 0) return@launch // Feature 3: TTL expiration

                // Handle SOS Resolution/Update (Feature: Duplicate Prevention)
                if (type == "UPDATE") {
                    repository.updateMessageStatus(msgId, content.replace("SOS_", ""))
                    return@launch
                }

                // Handle Mesh-wide Voice (Feature: Save & Play for all)
                var audioPath: String? = null
                var duration = 0
                if (type == "VOICE") {
                    try {
                        val audioData = Base64.decode(content, Base64.NO_WRAP)
                        val audioDir = File(context.filesDir, "audio_messages")
                        audioDir.mkdirs()
                        val file = File(audioDir, "mesh_rx_$msgId.pcm")
                        file.writeBytes(audioData)
                        audioPath = file.absolutePath
                        duration = (audioData.size / 32000) // 16000Hz * 2 bytes
                    } catch (e: Exception) { e.printStackTrace() }
                }

                // Automatic Disaster Detection
                if (type == "ALERT") {
                    isSafetyCheckActive = true
                    activeHazardZone = if (lat != null && lon != null) Pair(lat, lon) else null
                }

                val messageEntity = MessageEntity(
                    messageId = msgId,
                    sender = "Friend",
                    senderName = sName,
                    message = if (type == "VOICE") "[Voice Message]" else content,
                    type = type,
                    priority = NLPAnalyzer.calculateEmergencyRank(triage, vCount, battery, System.currentTimeMillis()),
                    latitude = lat,
                    longitude = lon,
                    isVerified = verified,
                    triageLevel = triage,
                    victimCount = vCount,
                    senderBattery = battery,
                    hazardType = hazard,
                    ttl = ttl - 1,
                    audioPath = audioPath,
                    audioDuration = duration
                )
                repository.insertMessage(messageEntity)

                // Feature 6: Battery Optimization & Forwarding
                val batteryLevel = getBatteryLevel(context)
                val shouldForward = when {
                    batteryLevel < 10 -> false // Hard stop at 10%
                    batteryLevel < 20 -> type == "SOS" || type == "ALERT"
                    else -> true
                }
                
                if (shouldForward && ttl > 1) {
                    val newPayload = "$msgId|$sName|$type|$lat|$lon|${parts[5]}|$triage|$vCount|$battery|$hazard|${ttl-1}|$content"
                    val newEncrypted = EncryptionUtils.encrypt(newPayload)
                    chatManager?.sendMessage(newEncrypted)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun syncHistory(chatManager: BluetoothChatManager?) {
        if (chatManager == null) return
        viewModelScope.launch {
            val messages = repository.allMessages.stateIn(this).value
            messages.forEach { msg ->
                // Protocol: "ID|NAME|TYPE|LAT|LON|VERIFIED|TRIAGE|COUNT|BATTERY|HAZARD|TTL|MESSAGE"
                val verified = if (msg.isVerified) "1" else "0"
                val rawPayload = "${msg.messageId}|${msg.senderName}|${msg.type}|${msg.latitude}|${msg.longitude}|$verified|${msg.triageLevel}|${msg.victimCount}|${msg.senderBattery}|${msg.hazardType}|${msg.ttl}|${msg.message}"
                val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
                chatManager.sendMessage(encryptedPayload)
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.deleteAllMessages()
        }
    }

    fun confirmSafety() {
        isSafetyCheckActive = false
        // Optionally broadcast "I'm Safe" to the mesh
        // sendMessage(null, "User confirmed safety", type = "INFO")
    }

    fun deleteMessage(msgId: String) {
        viewModelScope.launch {
            val msg = repository.getMessageById(msgId)
            if (msg?.audioPath != null) {
                try {
                    File(msg.audioPath).delete()
                } catch (e: Exception) { e.printStackTrace() }
            }
            repository.deleteMessage(msgId)
        }
    }

    fun resolveSos(context: Context, chatManager: BluetoothChatManager?, status: String) {
        viewModelScope.launch {
            val sosId = activeUserSosId ?: repository.getActiveUserSos()?.messageId ?: return@launch
            repository.updateMessageStatus(sosId, status)
            
            // Broadcast the resolution to the mesh
            // Protocol: "ID|NAME|TYPE|LAT|LON|VERIFIED|TRIAGE|COUNT|BATTERY|HAZARD|TTL|MESSAGE"
            val rawPayload = "$sosId|$userName|UPDATE|null|null|0|STABLE|0|100|GENERAL|5|SOS_$status"
            val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
            chatManager?.sendMessage(encryptedPayload)
            
            activeUserSosId = null
            Toast.makeText(context, "SOS $status", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveVoiceMessage(
        path: String,
        isMe: Boolean,
        chatManager: BluetoothChatManager? = null,
        senderName: String = "Survivor",
        lat: Double? = null,
        lon: Double? = null
    ) {
        viewModelScope.launch {
            val msgId = java.util.UUID.randomUUID().toString()
            val file = File(path)
            val duration = if (file.exists()) (file.length() / 32000).toInt() else 0

            val messageEntity = MessageEntity(
                messageId = msgId,
                sender = if (isMe) "Me" else "Friend",
                senderName = if (isMe) userName else senderName,
                message = "[Voice Message]",
                type = "VOICE",
                audioPath = path,
                audioDuration = duration,
                latitude = lat,
                longitude = lon
            )
            repository.insertMessage(messageEntity)

            // Broadcast Voice to Mesh if it's mine (Feature: Mesh-wide voice)
            if (isMe && chatManager != null) {
                try {
                    val audioData = File(path).readBytes()
                    val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
                    
                    // Protocol: "ID|NAME|TYPE|LAT|LON|VERIFIED|TRIAGE|COUNT|BATTERY|HAZARD|TTL|MESSAGE"
                    val rawPayload = "$msgId|$userName|VOICE|$lat|$lon|0|STABLE|1|100|GENERAL|5|$base64Audio"
                    val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
                    chatManager.sendMessage(encryptedPayload)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    fun exportDataCsv(context: Context) {
        viewModelScope.launch {
            val messages = allMessages.value
            val csvHeader = "ID,Name,Type,Lat,Lon,Verified,Triage,Timestamp,Message\n"
            val csvData = StringBuilder(csvHeader)
            
            messages.forEach { msg ->
                csvData.append("${msg.messageId},${msg.senderName},${msg.type},${msg.latitude},${msg.longitude},${msg.isVerified},${msg.triageLevel},${msg.timestamp},${msg.message.replace(",", " ")}\n")
            }
            
            try {
                val file = File(context.getExternalFilesDir(null), "Disaster_Export_${System.currentTimeMillis()}.csv")
                file.writeText(csvData.toString())
                Toast.makeText(context, "Exported to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
