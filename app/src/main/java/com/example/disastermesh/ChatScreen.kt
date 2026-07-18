package com.example.disastermesh

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream

@Composable
fun ChatScreen(
    chatManagers: List<BluetoothChatManager>,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onLocationClick: (Double, Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var messageText by remember { mutableStateOf("") }
    val allMessages by viewModel.allMessages.collectAsState()
    val activeSosId = viewModel.activeUserSosId

    val messages = remember(allMessages, activeSosId) {
        // ALWAYS filter by ACTIVE status unless it's a critical alert
        val activeOnly = allMessages.filter { it.status == "ACTIVE" || it.type == "ALERT" }
        
        if (activeSosId != null) {
            // Priority Mode: Show my SOS and all other SOS/ALERTS from others
            activeOnly.filter { 
                it.messageId == activeSosId || 
                it.messageId == "BEACON-${Math.abs(activeSosId.hashCode() % 100000)}" ||
                it.type == "SOS" || 
                it.type == "ALERT"
            }
        } else {
            activeOnly
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Playback state tracking
    var playingMessageId by remember { mutableStateOf<String?>(null) }
    var playbackTimeLeft by remember { mutableIntStateOf(0) }

    LaunchedEffect(playingMessageId) {
        if (playingMessageId != null) {
            while (playbackTimeLeft > 0) {
                delay(1000)
                playbackTimeLeft -= 1
            }
            playingMessageId = null
        }
    }

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val voiceManager = remember { VoiceManager() }
    var isRecording by remember { mutableStateOf(false) }

    // State for receiving audio
    var receivingFile: File? by remember { mutableStateOf(null) }
    var receivingStream: FileOutputStream? by remember { mutableStateOf(null) }
    var receivingMetadata: String? by remember { mutableStateOf(null) }

    val haptic = LocalHapticFeedback.current

    chatManagers.forEach { manager ->
        LaunchedEffect(manager) {
            manager.startListening(
                onMessageReceived = { receivedContent ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.receiveAndForwardMessage(context, receivedContent, manager, chatManagers)
                },
                onAudioStart = { metadata ->
                    receivingMetadata = metadata
                    val audioDir = File(context.filesDir, "audio_messages")
                    audioDir.mkdirs()
                    receivingFile = File(audioDir, "rx_${System.currentTimeMillis()}.pcm")
                    receivingStream = FileOutputStream(receivingFile)
                },
                onAudioChunk = { audioData ->
                    try {
                        receivingStream?.write(audioData)
                        voiceManager.playChunk(audioData)
                    } catch (e: Exception) { e.printStackTrace() }
                },
                onAudioEnd = {
                    receivingStream?.close()
                    receivingFile?.let { file ->
                        val meta = receivingMetadata?.split("|")
                        val name = meta?.getOrNull(0) ?: "Survivor"
                        val lat = meta?.getOrNull(1)?.toDoubleOrNull()
                        val lon = meta?.getOrNull(2)?.toDoubleOrNull()
                        viewModel.saveVoiceMessage(
                            path = file.absolutePath,
                            isMe = false,
                            chatManagers = chatManagers,
                            senderName = name,
                            lat = lat,
                            lon = lon
                        )
                    }
                    receivingFile = null
                    receivingStream = null
                    receivingMetadata = null
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager.release()
            receivingStream?.close()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(
                    msg = msg,
                    isPlaying = playingMessageId == msg.messageId,
                    remainingTime = if (playingMessageId == msg.messageId) playbackTimeLeft else msg.audioDuration,
                    onLocationClick = onLocationClick,
                    onPlayAudio = { path -> 
                        playingMessageId = msg.messageId
                        playbackTimeLeft = msg.audioDuration
                        voiceManager.playAudioFile(path) { info ->
                            scope.launch(Dispatchers.Main) {
                                Toast.makeText(context, info, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDelete = { viewModel.deleteMessage(msg.messageId, chatManagers) }
                )
            }
        }

        Surface(
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                if (viewModel.isGatewayActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF00C853).copy(alpha = 0.1f))
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "GATEWAY ACTIVE: SYNCING MESH TO MOBILE NETWORK",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00C853),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var lastRecordingLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                    var currentRecordingPath by remember { mutableStateOf<String?>(null) }
                    
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    if (isRecording) {
                                        isRecording = false
                                        voiceManager.stopRecording()
                                        // Current multi-audio stream logic (simplified for now)
                                        chatManagers.forEach { it.stopAudioStream() }
                                        currentRecordingPath?.let { path ->
                                            val file = File(path)
                                            if (file.exists() && file.length() > 0) {
                                                Toast.makeText(context, "Voice Saved: ${file.length() / 1024} KB", Toast.LENGTH_SHORT).show()
                                                viewModel.saveVoiceMessage(
                                                    path = path,
                                                    isMe = true,
                                                    chatManagers = chatManagers,
                                                    lat = lastRecordingLocation?.first,
                                                    lon = lastRecordingLocation?.second
                                                )
                                            } else {
                                                Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        currentRecordingPath = null
                                        lastRecordingLocation = null
                                    } else {
                                        val audioDir = File(context.filesDir, "audio_messages")
                                        audioDir.mkdirs()
                                        val file = File(audioDir, "rec_${System.currentTimeMillis()}.pcm")
                                        currentRecordingPath = file.absolutePath
                                        
                                        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                                        val location = try {
                                            @SuppressLint("MissingPermission")
                                            val loc = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                                            loc
                                        } catch (e: Exception) { null }
                                        
                                        lastRecordingLocation = location?.let { it.latitude to it.longitude }
                                        
                                        isRecording = true
                                        val metadata = "${viewModel.userName}|${location?.latitude}|${location?.longitude}"
                                        chatManagers.forEach { it.startAudioStream(metadata) }
                                        
                                        voiceManager.startRecording(context, file) { data, size ->
                                            chatManagers.forEach { it.sendAudioChunk(data, size) }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    isRecording = false
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isRecording) Color.Red else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(24.dp)
                            )
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Voice",
                            tint = if (isRecording) Color.White else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (isRecording) "Recording Voice..." else "Mesh Message...") },
                        shape = RoundedCornerShape(28.dp),
                        enabled = !isRecording,
                        maxLines = 4
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val textToSend = messageText
                                messageText = ""
                                scope.launch {
                                    try {
                                        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                                        @SuppressLint("MissingPermission")
                                        val location = try {
                                            // Try last location first for speed, then current location
                                            fusedLocationClient.lastLocation.await() ?: 
                                            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                                        } catch (e: Exception) { null }
                                        
                                        viewModel.sendMessage(chatManagers, textToSend, lat = location?.latitude, lon = location?.longitude)
                                    } catch (e: Exception) {
                                        viewModel.sendMessage(chatManagers, textToSend)
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    msg: MessageEntity,
    isPlaying: Boolean = false,
    remainingTime: Int = 0,
    onLocationClick: (Double, Double) -> Unit,
    onPlayAudio: (String) -> Unit,
    onDelete: () -> Unit
) {
    val isMe = msg.sender == "Me"
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val haptic = LocalHapticFeedback.current

    val bubbleColor = when (msg.type) {
        "SOS" -> MaterialTheme.colorScheme.error
        "RESOURCE" -> MaterialTheme.colorScheme.secondary
        else -> if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (msg.type == "SOS" || msg.type == "RESOURCE" || isMe) Color.White 
                       else MaterialTheme.colorScheme.onSurfaceVariant

    var showDeleteDialog by remember { mutableStateOf(false) }
    val timeString = remember(msg.timestamp) { 
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)) 
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Message?") },
            text = { Text("This will remove the message from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("DELETE", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isMe) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (msg.isVerified) {
                    Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(10.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = msg.senderName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Black
                )
            }
        }
        
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showDeleteDialog = true 
                        },
                        onTap = {
                            if (msg.audioPath != null) {
                                onPlayAudio(msg.audioPath)
                            } else if (msg.latitude != null && msg.longitude != null) {
                                // Only jump to map on tap if it's NOT a voice message
                                onLocationClick(msg.latitude, msg.longitude)
                            }
                        }
                    )
                },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMe) 16.dp else 2.dp,
                bottomEnd = if (isMe) 2.dp else 16.dp
            ),
            color = bubbleColor,
            tonalElevation = if (msg.type == "SOS") 8.dp else 2.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (msg.type == "SOS" || msg.type == "VOICE" || (msg.type == "CHAT" && msg.latitude != null)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        val icon = when(msg.type) {
                            "SOS" -> Icons.Default.Warning
                            "VOICE" -> Icons.Default.Mic
                            else -> Icons.Default.LocationOn
                        }
                        Icon(icon, null, tint = contentColor.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
                        
                        val numericId = if (msg.messageId.startsWith("BEACON-")) {
                            msg.messageId.removePrefix("BEACON-")
                        } else {
                            Math.abs(msg.messageId.hashCode() % 100000).toString().padStart(5, '0')
                        }

                        val label = when(msg.type) {
                            "SOS" -> "SOS"
                            "VOICE" -> "VOICE"
                            else -> "LOCATION ATTACHED"
                        }
                        val triage = if (msg.type == "SOS") " | LEVEL: ${msg.triageLevel}" else ""
                        val idText = if (msg.type != "CHAT") " ID: #$numericId" else ""
                        
                        Text(" $label$idText$triage", fontWeight = FontWeight.Black, fontSize = 9.sp, color = contentColor.copy(alpha = 0.9f))
                    }
                }
                
                if (msg.audioPath != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = contentColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("VOICE_DATA.LOG", color = contentColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (isPlaying) "Playing: ${remainingTime}s" else "Duration: ${msg.audioDuration}s",
                                color = contentColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (msg.latitude != null && msg.longitude != null) {
                            IconButton(
                                onClick = { onLocationClick(msg.latitude, msg.longitude) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                val isRecent = System.currentTimeMillis() - msg.timestamp < 60000
                                Icon(
                                    imageVector = if (msg.type == "SOS" && isRecent) Icons.Default.GpsFixed else Icons.Default.Map,
                                    contentDescription = "See on Map",
                                    tint = if (msg.type == "SOS" && isRecent) Color.Green else contentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                } else {
                    TypingTextSimple(
                        text = msg.message,
                        style = MaterialTheme.typography.bodyMedium.copy(color = contentColor)
                    )
                    
                    if (msg.type == "SOS") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No. of person trap: ${msg.victimCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor.copy(alpha = 0.9f)
                        )
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (msg.latitude != null) {
                        Icon(Icons.Default.LocationOn, null, tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(10.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = timeString,
                        fontSize = 9.sp,
                        color = contentColor.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun TypingTextSimple(text: String, style: androidx.compose.ui.text.TextStyle) {
    var visibleText by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        if (visibleText.isEmpty()) {
            text.forEach { char ->
                visibleText += char
                delay(10)
            }
        } else {
            visibleText = text
        }
    }
    Text(text = visibleText, style = style)
}
