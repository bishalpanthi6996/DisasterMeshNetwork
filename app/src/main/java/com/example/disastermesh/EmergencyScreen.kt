package com.example.disastermesh

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha

@Composable
fun EmergencyScreen(viewModel: ChatViewModel, chatManager: BluetoothChatManager?) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var selectedTriage by remember { mutableStateOf("STABLE") }
    var victimCount by remember { mutableStateOf(1) }
    
    val activeSosId = viewModel.activeUserSosId

    LaunchedEffect(Unit) {
        viewModel.checkActiveSos()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(60.dp),
            tint = if (activeSosId != null) Color.Red else MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = if (activeSosId != null) "SOS ACTIVE" else "Emergency SOS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = if (activeSosId != null) Color.Red else MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (activeSosId == null) {
            // SOS Form
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("People needing help: $victimCount", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { if (victimCount > 1) victimCount-- }) { Text("-") }
                IconButton(onClick = { victimCount++ }) { Text("+") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Select Severity Level:", style = MaterialTheme.typography.labelLarge)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TriageButton("STABLE", Color.Gray, selectedTriage == "STABLE") { selectedTriage = "STABLE" }
                TriageButton("SERIOUS", Color(0xFFFFA000), selectedTriage == "SERIOUS") { selectedTriage = "SERIOUS" }
                TriageButton("CRITICAL", Color.Red, selectedTriage == "CRITICAL") { selectedTriage = "CRITICAL" }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Describe situation or medical needs...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val finalMsg = if (message.isBlank()) "Emergency help needed: $selectedTriage" else message
                    viewModel.sendSos(
                        context = context,
                        chatManager = chatManager,
                        content = finalMsg,
                        triage = selectedTriage,
                        victimCount = victimCount,
                        hazardType = viewModel.currentEmergencyMode
                    )
                    status = "🚨 $selectedTriage SOS BROADCASTING..."
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTriage == "CRITICAL") Color.Red else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(32.dp)
            ) {
                Text("ACTIVATE $selectedTriage SOS", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // Active SOS Controls
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your SOS signal is currently broadcasting to the mesh network.", 
                        textAlign = TextAlign.Center, 
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    // Consistent numeric ID generation
                    val numericId = Math.abs(activeSosId.hashCode() % 100000).toString().padStart(5, '0')
                    Text("RESCUE ID: #$numericId",
                        style = MaterialTheme.typography.labelSmall, 
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alpha(0.7f))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { viewModel.resolveSos(context, chatManager, "SOLVED") },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
            ) {
                Text("I HAVE BEEN RESCUED", fontWeight = FontWeight.Black)
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { viewModel.resolveSos(context, chatManager, "CANCELLED") },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.Red),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
            ) {
                Text("CANCEL SOS", fontWeight = FontWeight.Bold)
            }
        }

        if (status.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RowScope.TriageButton(label: String, color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) color else color.copy(alpha = 0.2f),
            contentColor = if (isSelected) Color.White else color
        ),
        contentPadding = PaddingValues(4.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
