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
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.tasks.await
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
    private val processingIds = mutableSetOf<String>()
    private val relayCooldowns = mutableMapOf<String, Long>()
    
    // Feature: Global Anti-Duplication Memory
    private val processedMessageIds = mutableSetOf<String>()
    
    // Feature: Persistent Anti-Spam Memory (Does not clear on wipe)
    private val permanentSpamFilter = mutableSetOf<String>()
    
    var nearbyNodeCount by mutableIntStateOf(0)
    
    // Live Tracking States
    private var locationBroadcasterJob: Job? = null
    private var lastBroadcastLat: Double? = null
    private var lastBroadcastLon: Double? = null
    
    // Hybrid Network States
    var isGatewayActive by mutableStateOf(false)
    var cloudSyncStatus by mutableStateOf("Standby") // Standby, Syncing, Online
    
    // Feature: Disaster Feed Tracking
    var activeDisasterAlert by mutableStateOf<DisasterFeed?>(null)
    private var disasterMonitorJob: Job? = null

    private val retrofit = Retrofit.Builder()
        .baseUrl("http://10.0.2.2:8080/api/v1/") // Localhost for Android Emulator
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private val apiService = retrofit.create(ApiService::class.java)

    val allMessages: StateFlow<List<MessageEntity>> = repository.allMessages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // Feature: Isolation Filter for UI
    val filteredMessages: StateFlow<List<MessageEntity>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
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
                    checkForDisasterFeeds(context)
                    checkForNearbyCloudSos(context)
                }
                delay(10000) // Check every 10 seconds
            }
        }
    }

    private suspend fun checkForNearbyCloudSos(context: Context) {
        if (!isGatewayActive) return
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            @SuppressLint("MissingPermission")
            val myLoc = fusedLocationClient.lastLocation.await() ?: return
            
            val response = apiService.getNearbySos(myLoc.latitude, myLoc.longitude, 5.0) // 5km radius
            if (response.isSuccessful) {
                val nearbySosList = response.body() ?: return
                nearbySosList.forEach { sos ->
                    // Standardize SOS ID
                    val msgId = if (sos.messageId.startsWith("BEACON-")) sos.messageId 
                                else "BEACON-${Math.abs(sos.messageId.hashCode() % 100000)}"
                    
                    // Don't add if it's our own
                    if (msgId == activeUserSosId) return@forEach
                    
                    if (!repository.exists(msgId)) {
                        val entity = sos.copy(
                            messageId = msgId,
                            sender = "Nearby Survivor (Cloud)",
                            isCloudSynced = true
                        )
                        repository.insertMessage(entity)
                        processedMessageIds.add(msgId)
                        
                        // If it's a new critical SOS from cloud, notify the user
                        if (entity.triageLevel == "CRITICAL") {
                            Toast.makeText(context, "📡 CLOUD RESCUE ALERT: Nearby SOS Detected!", Toast.LENGTH_LONG).show()
                            activeHazardZone = Pair(entity.latitude ?: 0.0, entity.longitude ?: 0.0)
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun checkForDisasterFeeds(context: Context) {
        try {
            val response = apiService.getActiveDisasters()
            if (response.isSuccessful) {
                val disasters = response.body() ?: return
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                @SuppressLint("MissingPermission")
                val myLoc = fusedLocationClient.lastLocation.await() ?: return
                
                for (disaster in disasters) {
                    val distance = calculateDistance(myLoc.latitude, myLoc.longitude, disaster.latitude, disaster.longitude)
                    if (distance <= disaster.radiusKm * 1000) {
                        // User is in impact area!
                        activeDisasterAlert = disaster
                        isSafetyCheckActive = true
                        break
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in meters
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(dLambda / 2) * Math.sin(dLambda / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private suspend fun performCloudSync() {
        // Feature 11: Hybrid Mobile Network Sync
        // We find all messages not yet in the cloud and 'upload' them
        val unsynced = repository.allMessages.stateIn(viewModelScope).value.filter { !it.isCloudSynced }
        if (unsynced.isEmpty()) return

        cloudSyncStatus = "Syncing ${unsynced.size} nodes..."
        
        unsynced.forEach { msg ->
            try {
                val response = apiService.syncMessage(msg)
                if (response.isSuccessful) {
                    repository.updateSyncStatus(msg.messageId, true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        cloudSyncStatus = "Cloud Synchronized"
    }

    private fun getBatteryLevel(context: Context): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    fun sendSos(
        context: Context,
        chatManagers: List<BluetoothChatManager>,
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
            @SuppressLint("MissingPermission")
            val location = try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            } catch (e: Exception) {
                null
            }
            
            sendMessage(
                chatManagers = chatManagers,
                content = content,
                type = "SOS",
                lat = location?.latitude,
                lon = location?.longitude,
                triage = triage,
                victimCount = victimCount,
                hazardType = hazardType
            )

            // Feature 12: Start Live Location Broadcaster
            startLiveLocationBroadcast(context, chatManagers)
        }
    }

    fun markResource(context: Context, chatManagers: List<BluetoothChatManager>, resourceName: String) {
        viewModelScope.launch {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val location = try {
                @SuppressLint("MissingPermission")
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            } catch (e: Exception) {
                null
            }
            sendMessage(
                chatManagers = chatManagers,
                content = resourceName,
                type = "RESOURCE",
                lat = location?.latitude,
                lon = location?.longitude
            )
        }
    }

    fun sendMessage(
        chatManagers: List<BluetoothChatManager>,
        content: String,
        type: String = "CHAT",
        lat: Double? = null,
        lon: Double? = null,
        triage: String = "STABLE",
        victimCount: Int = 1,
        hazardType: String = "GENERAL",
        messageId: String? = null
    ) {
        if (content.isBlank()) return

        viewModelScope.launch {
            // NLP Analysis for Triage & AI Ranking (Feature 1)
            val analyzedTriage = if (type == "SOS") NLPAnalyzer.analyzeTriage(content) else triage
            val battery = 85 // Mock battery for now
            val calculatedRank = NLPAnalyzer.calculateEmergencyRank(analyzedTriage, victimCount, battery, System.currentTimeMillis())
            
            // Standardize SOS ID to the Beacon format (Hashed numeric)
            // This ensures Classic BT and BLE signals use the EXACT SAME ID for deduplication
            val rawId = messageId ?: java.util.UUID.randomUUID().toString()
            val msgId = if (type == "SOS") {
                val numericHash = if (rawId.startsWith("BEACON-")) rawId.removePrefix("BEACON-").toIntOrNull()
                                 else Math.abs(rawId.hashCode() % 100000)
                "BEACON-${numericHash}"
            } else rawId
            
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
            chatManagers.forEach { it.sendMessage(encryptedPayload) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveLocationBroadcast(context: Context, chatManagers: List<BluetoothChatManager>) {
        locationBroadcasterJob?.cancel()
        locationBroadcasterJob = viewModelScope.launch {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            while (isActive && activeUserSosId != null) {
                delay(30000) // Broadcast every 30 seconds
                val location = try {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                } catch (e: Exception) { null }

                location?.let {
                    // Feature: Significant Movement Filter (Prevents battery drain and network spam)
                    val dist = if (lastBroadcastLat != null && lastBroadcastLon != null) {
                        calculateDistance(it.latitude, it.longitude, lastBroadcastLat!!, lastBroadcastLon!!)
                    } else 100.0
                    
                    if (dist > 5.0) { // Only broadcast if moved more than 5 meters
                        val sosId = activeUserSosId ?: return@let
                        val rawPayload = "$sosId|$userName|LOC_UPDATE|${it.latitude}|${it.longitude}|0|STABLE|0|100|EARTHQUAKE|3|UPDATE"
                        val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
                        chatManagers.forEach { it.sendMessage(encryptedPayload) }
                        
                        lastBroadcastLat = it.latitude
                        lastBroadcastLon = it.longitude
                        
                        // Update local DB for self-view
                        repository.updateMessageLocation(sosId, it.latitude, it.longitude)
                    }
                }
            }
        }
    }

    fun receiveAndForwardMessage(context: Context, encryptedPayload: String, currentManager: BluetoothChatManager?, allManagers: List<BluetoothChatManager>) {
        viewModelScope.launch {
            try {
                val payload = EncryptionUtils.decrypt(encryptedPayload) ?: return@launch
                val parts = payload.split("|", limit = 12)
                if (parts.size < 12) return@launch

                val rawMsgId = parts[0]
                val type = parts[2]
                
                // SOS ID Standardization
                val msgId = if (type == "SOS" || type == "LOC_UPDATE") {
                    val numericHash = if (rawMsgId.startsWith("BEACON-")) rawMsgId.removePrefix("BEACON-").toIntOrNull()
                                     else Math.abs(rawMsgId.hashCode() % 100000)
                    "BEACON-${numericHash}"
                } else rawMsgId

                // GLOBAL DEDUPLICATION: If we've already processed this exact message, stop immediately.
                if (processedMessageIds.contains(msgId) && type != "LOC_UPDATE" && type != "UPDATE") return@launch
                if (repository.exists(msgId) && type != "LOC_UPDATE" && type != "UPDATE") {
                    processedMessageIds.add(msgId)
                    return@launch
                }

                val sName = parts[1]
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

                // Handle Live Location Update (Feature 12)
                if (type == "LOC_UPDATE") {
                    if (lat != null && lon != null) {
                        repository.updateMessageLocation(msgId, lat, lon)
                        // Forward the update
                        if (ttl > 1) {
                            val newPayload = "$msgId|$sName|LOC_UPDATE|$lat|$lon|0|STABLE|0|100|EARTHQUAKE|${ttl-1}|UPDATE"
                            val enc = EncryptionUtils.encrypt(newPayload)
                            allManagers.forEach { manager ->
                                if (manager != currentManager) {
                                    manager.sendMessage(enc)
                                }
                            }
                        }
                    }
                    return@launch
                }

                // Handle SOS Resolution/Update (Feature: Duplicate Prevention)
                if (type == "UPDATE") {
                    val statusStr = content.replace("SOS_", "")
                    if (statusStr == "SOLVED" || statusStr == "CANCELLED" || statusStr == "RESOLVED") {
                        repository.deleteMessage(msgId)
                        processedMessageIds.remove(msgId)
                        // Also clear from permanent filter to allow future SOS from same node
                        val filterPrefix = "${msgId}_${lat}_${lon}"
                        permanentSpamFilter.remove("${filterPrefix}_STABLE")
                        permanentSpamFilter.remove("${filterPrefix}_SERIOUS")
                        permanentSpamFilter.remove("${filterPrefix}_CRITICAL")
                    } else {
                        repository.updateMessageStatus(msgId, statusStr)
                    }

                    // Forward the update to the rest of the mesh
                    if (ttl > 1) {
                        val newPayload = "$msgId|$sName|UPDATE|$lat|$lon|${parts[5]}|$triage|$vCount|$battery|$hazard|${ttl-1}|$content"
                        val enc = EncryptionUtils.encrypt(newPayload)
                        allManagers.forEach { manager ->
                            if (manager != currentManager) {
                                manager.sendMessage(enc)
                            }
                        }
                    }
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
                if (type == "ALERT" || type == "SOS") {
                    if (type == "ALERT") {
                        isSafetyCheckActive = true
                        activeHazardZone = if (lat != null && lon != null) Pair(lat, lon) else null
                    }
                    // For SOS, ensure we notify the user even if they are in Comms
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
                processedMessageIds.add(msgId)

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
                    allManagers.forEach { manager ->
                        if (manager != currentManager) {
                            manager.sendMessage(newEncrypted)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun syncHistory(chatManager: BluetoothChatManager) {
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

    fun clearAllData(context: Context, chatManagers: List<BluetoothChatManager> = emptyList()) {
        viewModelScope.launch {
            if (activeUserSosId != null) {
                val sosId = activeUserSosId!!
                // 1. Broadcast "CANCELLED" status to the mesh (Classic Bluetooth)
                val rawPayload = "$sosId|$userName|UPDATE|0.0|0.0|0|STABLE|0|0|GENERAL|5|SOS_CANCELLED"
                val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
                chatManagers.forEach { it.sendMessage(encryptedPayload) }
                
                // 2. Update status to trigger BLE RESOLVED broadcast in MainActivity
                repository.updateMessageStatus(sosId, "CANCELLED")
                
                // 3. WAIT: Give MainActivity's loop (which runs every 5s) time to see this
                // We'll wait 6 seconds to be absolutely sure the broadcast starts.
                delay(6000) 

                activeUserSosId = null
                locationBroadcasterJob?.cancel()
                locationBroadcasterJob = null
            }
            repository.deleteAllMessages()
            processingIds.clear()
            relayCooldowns.clear()
            activeUserSosId = null
        }
    }

    fun confirmSafety(context: Context, isSafe: Boolean, chatManagers: List<BluetoothChatManager> = emptyList()) {
        isSafetyCheckActive = false
        val disaster = activeDisasterAlert ?: return
        
        viewModelScope.launch {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            @SuppressLint("MissingPermission")
            val loc = fusedLocationClient.lastLocation.await()
            
            // SIMULATED BACKEND REPORT
            if (isGatewayActive) {
                Toast.makeText(context, "📡 REPORTING TO GOVT: User is ${if(isSafe) "SAFE" else "IN RISK"}", Toast.LENGTH_SHORT).show()
                // apiService.reportUserStatus(userName, isSafe, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)
            } else {
                Toast.makeText(context, "📶 [EDGE/SMS] REPORTING SAFETY STATUS...", Toast.LENGTH_SHORT).show()
            }
            
            if (!isSafe) {
                triggerAutoSos(context, "USER_REPORTED_RISK: ${disaster.name}", chatManagers)
            }
            
            activeDisasterAlert = null
        }
    }

    private var isSmsFallbackTriggered = false

    fun triggerAutoSos(context: Context, content: String, chatManagers: List<BluetoothChatManager> = emptyList()) {
        viewModelScope.launch {
            // Check if we already have an active SOS to prevent duplicates
            val existingSos = repository.getActiveUserSos()
            if (existingSos != null) {
                activeUserSosId = existingSos.messageId
                return@launch 
            }

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            @SuppressLint("MissingPermission")
            val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            
            // Create a single unique ID for the entire SOS event (Victim, Mesh, and Cloud)
            val uniqueSosId = "BEACON-${Math.abs(java.util.UUID.randomUUID().hashCode() % 100000)}"
            activeUserSosId = uniqueSosId // Set immediately to prevent echo detection

            // 1. Bluetooth Mesh Broadcast
            sendMessage(
                chatManagers = chatManagers,
                content = content,
                type = "SOS",
                lat = location?.latitude,
                lon = location?.longitude,
                triage = "CRITICAL",
                victimCount = 1,
                hazardType = activeDisasterAlert?.name ?: "EARTHQUAKE",
                messageId = uniqueSosId
            )
            
            // Feature 12: Start Live Location Broadcaster
            startLiveLocationBroadcast(context, chatManagers)

            // 2. Cellular/Low-Speed Fallback (SERVER SIDE RESCUE)
            delay(1500)
            val mySos = repository.getMessageById(uniqueSosId)
            if (mySos != null) {
                try {
                    val response = apiService.syncMessage(mySos)
                    if (response.isSuccessful) {
                        repository.updateSyncStatus(uniqueSosId, true)
                        Toast.makeText(context, "✅ SERVER COORDINATION ACTIVE", Toast.LENGTH_SHORT).show()
                    } else {
                        // Server reachable but error -> Fallback to SMS Gateway
                        sendSmsToGovernment(context, mySos)
                    }
                } catch (e: Exception) {
                    // No Internet -> Fallback to SMS Gateway
                    sendSmsToGovernment(context, mySos)
                }
            }
        }
    }

    private fun sendSmsToGovernment(context: Context, sos: MessageEntity) {
        if (isSmsFallbackTriggered) return // Prevent duplicate SMS costs
        
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            // Protocol: SOS|ID|LAT|LON|TRIAGE|NAME
            val smsText = "SOS|${sos.messageId.removePrefix("BEACON-")}|${sos.latitude}|${sos.longitude}|${sos.triageLevel}|$userName"
            
            // In a real scenario, "122" or "911" would be a specialized Server SMS Gateway number
            // smsManager.sendTextMessage("122", null, smsText, null, null)
            
            Toast.makeText(context, "📶 CLOUD OFFLINE: Relaying SOS via SMS Gateway...", Toast.LENGTH_LONG).show()
            isSmsFallbackTriggered = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "❌ CRITICAL: No Cloud and No Cell Signal!", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteMessage(msgId: String, chatManagers: List<BluetoothChatManager> = emptyList()) {
        viewModelScope.launch {
            val msg = repository.getMessageById(msgId)
            
            // If deleting our own active SOS, trigger a resolution broadcast first
            if (msgId == activeUserSosId) {
                // Broadcast "CANCELLED" status to the mesh before deleting locally
                // This ensures other devices remove it too
                val rawPayload = "$msgId|$userName|UPDATE|0.0|0.0|0|STABLE|0|0|GENERAL|5|SOS_CANCELLED"
                val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
                chatManagers.forEach { it.sendMessage(encryptedPayload) }
                
                // Update status to trigger BLE RESOLVED broadcast in MainActivity
                repository.updateMessageStatus(msgId, "CANCELLED")
                
                // 3. WAIT: Give MainActivity's loop (which runs every 5s) time to see this
                // We'll wait 6 seconds to be absolutely sure the broadcast starts.
                delay(6000)

                activeUserSosId = null
                locationBroadcasterJob?.cancel()
                locationBroadcasterJob = null
            }

            if (msg?.audioPath != null) {
                try {
                    File(msg.audioPath).delete()
                } catch (e: Exception) { e.printStackTrace() }
            }
            repository.deleteMessage(msgId)
            processedMessageIds.remove(msgId)
        }
    }

    fun resolveSos(context: Context, chatManagers: List<BluetoothChatManager>, status: String) {
        viewModelScope.launch {
            val sosId = activeUserSosId ?: return@launch
            
            // Mark as solved/cancelled in DB. 
            // MainActivity will detect this status change and broadcast the "RESOLVED" beacon.
            repository.updateMessageStatus(sosId, status)

            // Also broadcast via Classic Bluetooth if connected
            // Protocol: "ID|NAME|TYPE|LAT|LON|VERIFIED|TRIAGE|COUNT|BATTERY|HAZARD|TTL|MESSAGE"
            val rawPayload = "$sosId|$userName|UPDATE|0.0|0.0|0|STABLE|0|0|GENERAL|5|SOS_$status"
            val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
            chatManagers.forEach { it.sendMessage(encryptedPayload) }
            
            Toast.makeText(context, "Broadcasting $status status to Mesh...", Toast.LENGTH_SHORT).show()
        }
    }

    fun finalizeSosRemoval() {
        viewModelScope.launch {
            val sosId = activeUserSosId ?: return@launch
            repository.deleteMessage(sosId)
            activeUserSosId = null
            locationBroadcasterJob?.cancel()
            locationBroadcasterJob = null
        }
    }

    fun handleBleSosDetection(id: String, lat: Double, lon: Double, triage: String, vCount: Int, onForward: () -> Unit = {}) {
        // 1. Global Network Cleanup (MUST be first to bypass deduplication)
        if (triage == "RESOLVED") {
            viewModelScope.launch {
                repository.deleteMessage(id)
                // Remove from processed set so it can be re-added if a NEW SOS starts later
                processedMessageIds.remove(id)
                val spamKeyBase = "${id}_${lat}_${lon}"
                permanentSpamFilter.remove("${spamKeyBase}_STABLE")
                permanentSpamFilter.remove("${spamKeyBase}_SERIOUS")
                permanentSpamFilter.remove("${spamKeyBase}_CRITICAL")
                
                // Forward the erasure to the rest of the mesh
                onForward()
            }
            return
        }

        // 2. Identity Check: STOP ECHOES. If we are the one broadcasting this ID, IGNORE the detection.
        if (id == activeUserSosId) return 
        if (processedMessageIds.contains(id)) return
        
        // 3. Persistent Filter: If we've seen this exact ID + Coord today, don't re-add it
        val spamKey = "${id}_${lat}_${lon}_$triage"
        if (permanentSpamFilter.contains(spamKey)) return

        // 4. Atomic Lock: Prevent simultaneous processing
        if (processingIds.contains(id)) return
        
        if (lat == 0.0 || lon == 0.0) return 

        processingIds.add(id)
        permanentSpamFilter.add(spamKey) 
        
        viewModelScope.launch {
            try {
                // 5. Duplicate Check
                val existing = repository.getMessageById(id)
                if (existing != null) {
                    processedMessageIds.add(id)
                    if (existing.sender == "Me") return@launch
                    if (existing.latitude == lat && existing.longitude == lon && existing.triageLevel == triage) {
                        return@launch
                    }
                }
                
                processedMessageIds.add(id)
                
                val messageEntity = MessageEntity(
                    messageId = id,
                    sender = "Nearby Survivor",
                    senderName = "Mesh Node",
                    message = "CRITICAL SOS: EMERGENCY ASSISTANCE REQUIRED",
                    type = "SOS",
                    latitude = lat,
                    longitude = lon,
                    triageLevel = triage,
                    victimCount = vCount,
                    priority = NLPAnalyzer.calculateEmergencyRank(triage, vCount, -1, System.currentTimeMillis()),
                    status = "ACTIVE"
                )
                repository.insertMessage(messageEntity)
                
                // 6. Intelligent Relay
                val now = System.currentTimeMillis()
                val lastRelay = relayCooldowns[id] ?: 0L
                if (now - lastRelay > 15000) {
                    relayCooldowns[id] = now
                    onForward()
                }
            } finally {
                delay(2000)
                processingIds.remove(id)
            }
        }
    }

    fun saveVoiceMessage(
        path: String,
        isMe: Boolean,
        chatManagers: List<BluetoothChatManager> = emptyList(),
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
            if (isMe && chatManagers.isNotEmpty()) {
                try {
                    val audioData = File(path).readBytes()
                    val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
                    
                    // Protocol: "ID|NAME|TYPE|LAT|LON|VERIFIED|TRIAGE|COUNT|BATTERY|HAZARD|TTL|MESSAGE"
                    val rawPayload = "$msgId|$userName|VOICE|$lat|$lon|0|STABLE|1|100|GENERAL|5|$base64Audio"
                    val encryptedPayload = EncryptionUtils.encrypt(rawPayload)
                    chatManagers.forEach { it.sendMessage(encryptedPayload) }
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
