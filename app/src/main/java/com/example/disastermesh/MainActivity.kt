package com.example.disastermesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import android.content.Context
import android.util.Log
import android.location.LocationManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.provider.Settings
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GpsFixed
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.example.disastermesh.ui.theme.DisasterMeshTheme

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothHelper: BluetoothManagerHelper
    private lateinit var bleManager: BLEManager
    private lateinit var bluetoothConnectionManager: BluetoothConnectionManager
    private lateinit var database: MessageDatabase
    private lateinit var repository: MessageRepository
    
    private var chatManager by mutableStateOf<BluetoothChatManager?>(null)
    private var currentScreen by mutableStateOf("dashboard")
    private var showPermissionIntro by mutableStateOf(false)
    private var showStatusPopup by mutableStateOf(false)

    private val bluetoothPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val criticalPermissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                criticalPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
                criticalPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                criticalPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }

            val allCriticalGranted = criticalPermissions.all { permissions[it] == true }

            if (allCriticalGranted) {
                Toast.makeText(this, "Mesh Network Ready ✅", Toast.LENGTH_SHORT).show()
                showPermissionIntro = false
                bluetoothConnectionManager.listen()
                ensureDiscoverable()
            } else {
                Toast.makeText(this, "Permissions Required for Mesh Protocol", Toast.LENGTH_LONG).show()
                showPermissionIntro = true
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        database = Room.databaseBuilder(
            applicationContext,
            MessageDatabase::class.java,
            "message_database"
        ).fallbackToDestructiveMigration()
            .build()
        repository = MessageRepository(database.messageDao())

        bluetoothHelper = BluetoothManagerHelper(this)
        bleManager = BLEManager(this)
        bluetoothConnectionManager = BluetoothConnectionManager(this)
        
        enableEdgeToEdge()
        checkInitialPermissions()

        setContent {
            val viewModel: ChatViewModel = viewModel(
                factory = ChatViewModelFactory(repository)
            )
            val haptic = LocalHapticFeedback.current
            val context = LocalContext.current

            // Hybrid Network Monitoring (Bluetooth + Mobile Network)
            LaunchedEffect(Unit) {
                viewModel.startNetworkMonitoring(context)
            }

            // BLE Mesh Beacon Logic (Zero-Pairing Instant Discovery & RELAY)
            LaunchedEffect(showStatusPopup) {
                if (!showStatusPopup && allRequirementsMet()) {
                    bleManager.startMeshScanning()
                }
                bleManager.onSosDetected = { id, lat, lon, triage, vCount ->
                    // This creates the SOS in the database INSTANTLY
                    viewModel.handleBleSosDetection(id, lat, lon, triage, vCount) {
                        // Relay it with the EXACT SAME ID and VICTIM COUNT to the next hop
                        bleManager.startSosBroadcast(id, lat, lon, triage, vCount, isRelay = true)
                    }
                }
            }

            // Sync BLE Broadcast with local SOS state (Original Source)
            LaunchedEffect(viewModel.activeUserSosId) {
                val sosId = viewModel.activeUserSosId
                if (sosId != null) {
                    val activeSos = repository.getActiveUserSos()
                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                    @SuppressLint("MissingPermission")
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            // Start shouting our SOS with our specific victim count
                            bleManager.startSosBroadcast(
                                sosId = sosId,
                                lat = lastLoc.latitude,
                                lon = lastLoc.longitude,
                                triage = activeSos?.triageLevel ?: "CRITICAL",
                                vCount = activeSos?.victimCount ?: 1
                            )
                        }
                    }
                } else {
                    bleManager.stopSosBroadcast()
                }
            }

            // Automatic Mesh Sync Logic (Classic Bluetooth Discovery used for Node Counting)
            LaunchedEffect(Unit) {
                while(true) {
                    if (bluetoothHelper.bluetoothAdapter?.isEnabled == true) {
                        bluetoothHelper.startDiscovery()
                        // Update BLE Manager with latest nearby node count for relay decisions
                        bleManager.updateNearbyNodeCount(bluetoothHelper.nearbyDevices.size)
                    }
                    delay(15000) // Scan every 15 seconds
                }
            }

            // Periodic check for hardware status
            LaunchedEffect(Unit) {
                while(true) {
                    val allMet = allRequirementsMet()
                    // Auto-show if requirements are missing
                    if (!allMet) {
                        showStatusPopup = true
                    } 
                    // Auto-hide if everything is now fixed
                    else {
                        showStatusPopup = false
                    }
                    delay(5000)
                }
            }

            LaunchedEffect(Unit) {
                bluetoothConnectionManager.onConnected = { socket ->
                    val manager = BluetoothChatManager(socket)
                    chatManager = manager
                    viewModel.syncHistory(manager)
                    
                    // Only jump to chat if we are on dashboard or nearby screen (user likely wants to see)
                    if (currentScreen == "dashboard" || currentScreen == "nearby") {
                        currentScreen = "chat"
                    }
                }
            }
            
            // Sensor-based Impact Detection (User couldn't reach phone)
            DisposableEffect(Unit) {
                val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? SensorManager
                val accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
                
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            if (it.values.size >= 3) {
                                val x = it.values[0]
                                val y = it.values[1]
                                val z = it.values[2]
                                val gForce = sqrt(x*x + y*y + z*z) / 9.8f
                                
                                // If a significant impact (crash/fall) is detected (> 4G)
                                if (gForce > 4.0f) {
                                    viewModel.isSafetyCheckActive = true
                                }
                            }
                        }
                    }
                    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
                }
                
                accelerometer?.let {
                    sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                onDispose { 
                    sensorManager?.unregisterListener(listener) 
                }
            }

            LaunchedEffect(viewModel.isSafetyCheckActive) {
                if (viewModel.isSafetyCheckActive) {
                    var elapsedSeconds = 0
                    while (viewModel.isSafetyCheckActive) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        
                        // After 6 intervals (30 seconds), auto-broadcast SOS
                        if (elapsedSeconds >= 30) {
                            viewModel.sendSos(context, chatManager, "AUTO-SOS: No user response after disaster alert.")
                            viewModel.confirmSafety() // Close overlay
                            break
                        }
                        
                        delay(5000) // Repeat every 5 seconds
                        elapsedSeconds += 5
                    }
                }
            }

            DisasterMeshTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (showPermissionIntro) {
                        PermissionIntroScreen { requestAllPermissions() }
                    } else {
                        BackHandler(enabled = currentScreen != "dashboard") {
                            currentScreen = "dashboard"
                        }

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            topBar = {
                                CenterAlignedTopAppBar(
                                    title = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (viewModel.isGovernmentUser) {
                                                    Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                                Text(
                                                    text = "DISASTER MESH",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
                                                )
                                            }
                                            Text("ID: ${viewModel.userName.uppercase()}", style = MaterialTheme.typography.labelSmall)
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = { currentScreen = "guide" }) {
                                            Icon(Icons.Default.HealthAndSafety, contentDescription = "Survival Guide", tint = MaterialTheme.colorScheme.tertiary)
                                        }
                                    },
                                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.background,
                                        titleContentColor = MaterialTheme.colorScheme.onBackground
                                    )
                                )
                            },
                            bottomBar = {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 8.dp
                                ) {
                                    NavigationBarItem(
                                        selected = currentScreen == "dashboard",
                                        onClick = { currentScreen = "dashboard" },
                                        icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                                        label = { Text("HUD") }
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == "map",
                                        onClick = { currentScreen = "map" },
                                        icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                                        label = { Text("Radar") }
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == "chat",
                                        onClick = { currentScreen = "chat" },
                                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                                        label = { Text("Comms") }
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == "nearby",
                                        onClick = { currentScreen = "nearby" },
                                        icon = { Icon(Icons.Default.Hub, contentDescription = null) },
                                        label = { Text("Nodes") }
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.padding(innerPadding)) {
                                when (currentScreen) {
                                    "dashboard" -> DashboardScreen(
                                        viewModel = viewModel,
                                        onSendSOSClick = { currentScreen = "emergency" },
                                        onResourceClick = { currentScreen = "resources" },
                                        onIdentityClick = { currentScreen = "identity" },
                                        onShareAppClick = { AppSharingUtils.shareApp(this@MainActivity) }
                                    )
                                    "identity" -> IdentityScreen(
                                        viewModel = viewModel,
                                        onBack = { currentScreen = "dashboard" }
                                    )
                                    "emergency" -> EmergencyScreen(viewModel, chatManager)
                                    "nearby" -> NearbyDevicesScreen(
                                        bluetoothHelper = bluetoothHelper,
                                        onConnectClick = { address ->
                                            bluetoothConnectionManager.connect(address)
                                        }
                                    )
                                    "chat" -> ChatScreen(
                                        chatManager = chatManager,
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize(),
                                        onLocationClick = { lat, lon ->
                                            viewModel.mapTargetLocation = Pair(lat, lon)
                                            currentScreen = "map"
                                        }
                                    )
                                    "map" -> MapScreen(viewModel)
                                    "resources" -> ResourceScreen(viewModel, chatManager)
                                    "guide" -> SurvivalGuideScreen()
                                }

                                if (viewModel.isSafetyCheckActive) {
                                    SafetyCheckOverlay(
                                        onSafeClick = { viewModel.confirmSafety() },
                                        onSosClick = {
                                            viewModel.confirmSafety()
                                            currentScreen = "emergency"
                                        }
                                    )
                                }

                                if (showStatusPopup) {
                                    RequirementPopup(
                                        onDismiss = { showStatusPopup = false },
                                        onOpenSettings = {
                                            if (!isBluetoothEnabled()) {
                                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                                startActivity(intent)
                                            } else if (!isLocationEnabled()) {
                                                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                            } else {
                                                requestAllPermissions()
                                            }
                                        },
                                        isBtOn = isBluetoothEnabled(),
                                        isGpsOn = isLocationEnabled(),
                                        isMicPerm = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkInitialPermissions() {
        val required = getRequiredPermissions()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isNotEmpty()) {
            showPermissionIntro = true
        } else {
            showPermissionIntro = false
            bluetoothConnectionManager.listen()
            ensureDiscoverable()
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun requestAllPermissions() {
        bluetoothPermissionLauncher.launch(getRequiredPermissions())
    }

    private fun ensureDiscoverable() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        if (bluetoothAdapter?.isEnabled == true) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivity(intent)
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        return bluetoothManager?.adapter?.isEnabled == true
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun allRequirementsMet(): Boolean {
        val permissions = getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        return permissions && isBluetoothEnabled() && isLocationEnabled()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothHelper.stopDiscovery()
    }
}

@Composable
fun RequirementPopup(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    isBtOn: Boolean,
    isGpsOn: Boolean,
    isMicPerm: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SettingsSuggest, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("System Requirements", fontWeight = FontWeight.Black)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("To maintain the mesh network and broadcast SOS signals, please ensure the following are active:")
                
                RequirementItem(
                    text = "Bluetooth Hardware",
                    isOn = isBtOn,
                    icon = Icons.Default.Bluetooth
                )
                RequirementItem(
                    text = "GPS Location",
                    isOn = isGpsOn,
                    icon = Icons.Default.GpsFixed
                )
                RequirementItem(
                    text = "Microphone Access",
                    isOn = isMicPerm,
                    icon = Icons.Default.Mic
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text("FIX NOW")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("LATER")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large
    )
}

@Composable
fun RequirementItem(text: String, isOn: Boolean, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isOn) Color(0xFF00C853) else Color.Red
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isOn) FontWeight.Normal else FontWeight.Bold,
            color = if (isOn) MaterialTheme.colorScheme.onSurface else Color.Red
        )
        Spacer(modifier = Modifier.weight(1f))
        if (isOn) {
            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00C853), modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Default.Error, null, tint = Color.Red, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SafetyCheckOverlay(onSafeClick: () -> Unit, onSosClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Red.copy(alpha = 0.9f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "DISASTER ALERT DETECTED",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                "Are you safe? The system will broadcast an SOS if you do not respond.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onSafeClick,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Red)
            ) {
                Text("I AM SAFE", fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onSosClick,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White)
            ) {
                Text("SEND SOS NOW", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PermissionIntroScreen(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Protocol Activation",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "This device will act as a mesh node. We require GPS, Bluetooth, and Audio permissions to facilitate peer-to-peer rescue operations.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = onGrantClick,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("INITIALIZE MESH PROTOCOL", fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}

@Composable
fun IdentityScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    var tempName by remember { mutableStateOf(viewModel.userName) }
    var isGov by remember { mutableStateOf(viewModel.isGovernmentUser) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Mesh Identity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = tempName,
            onValueChange = { tempName = it },
            label = { Text("Display Name") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = isGov, onCheckedChange = { isGov = it })
                Spacer(modifier = Modifier.width(8.dp))
                Text("Register as Government Node", style = MaterialTheme.typography.bodyMedium)
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                viewModel.userName = tempName
                viewModel.isGovernmentUser = isGov
                onBack()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("SAVE CREDENTIALS", fontWeight = FontWeight.Black)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ChatViewModel,
    onSendSOSClick: () -> Unit,
    onResourceClick: () -> Unit,
    onIdentityClick: () -> Unit,
    onShareAppClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val messages by viewModel.allMessages.collectAsState()
    val activeSOSCount = messages.count { it.type == "SOS" && it.status == "ACTIVE" && it.timestamp > System.currentTimeMillis() - 3600000 }
    val haptic = LocalHapticFeedback.current

    // Pulsing Animation for SOS
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Tactical Scanline Effect
        TacticalBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Emergency Mode Selector (Locked to Earthquake)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                val mode = "EARTHQUAKE"
                Button(
                    onClick = { viewModel.currentEmergencyMode = mode },
                    modifier = Modifier.fillMaxWidth(0.6f),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Tsunami, null, modifier = Modifier.size(12.dp)) // Tsunami/Earthquake related icon
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(mode, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Tactical HUD Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HudCard(
                    title = "CLOUD GATEWAY",
                    value = viewModel.cloudSyncStatus.uppercase(),
                    icon = if (viewModel.isGatewayActive) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    modifier = Modifier.weight(1.3f),
                    color = if (viewModel.isGatewayActive) Color(0xFF00C853) else Color.Gray
                )
                HudCard(
                    title = "ACTIVE SOS",
                    value = activeSOSCount.toString(),
                    icon = Icons.Default.Warning,
                    modifier = Modifier.weight(1f),
                    color = if (activeSOSCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }

            // SOS Section - Tactical Elevated with Pulsing Glow
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(180.dp)) {
                // Outer Pulse Rings
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = pulseScale * 1.2f, scaleY = pulseScale * 1.2f)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha), MaterialTheme.shapes.large)
                )

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSendSOSClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.WifiTetheringError,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "BROADCAST SOS",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    letterSpacing = 2.sp
                                )
                            )
                            Text("ZERO-NETWORK EMERGENCY SIGNAL", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Actions Grid
            DashboardButton(
                text = "Mark Supplies",
                subText = "Register Food/Water on Grid",
                icon = Icons.Default.AddLocationAlt,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onResourceClick()
                }
            )
            
            DashboardButton(
                text = "Identify",
                subText = "Profile & Official Credentials",
                icon = Icons.Default.Badge,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onIdentityClick()
                }
            )

            DashboardButton(
                text = "Deploy App",
                subText = "Push APK to Nearby Survivors",
                icon = Icons.Default.Share,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onShareAppClick()
                }
            )

            DashboardButton(
                text = "System Wipe",
                subText = "Emergency Data Deletion",
                icon = Icons.Default.DeleteForever,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.clearAllData()
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun TacticalBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanlineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "y"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Subtle grid
        val gridSize = 40.dp.toPx()
        for (x in 0..(width / gridSize).toInt()) {
            drawLine(
                color = Color.White.copy(alpha = 0.03f),
                start = androidx.compose.ui.geometry.Offset(x * gridSize, 0f),
                end = androidx.compose.ui.geometry.Offset(x * gridSize, height),
                strokeWidth = 1f
            )
        }
        for (y in 0..(height / gridSize).toInt()) {
            drawLine(
                color = Color.White.copy(alpha = 0.03f),
                start = androidx.compose.ui.geometry.Offset(0f, y * gridSize),
                end = androidx.compose.ui.geometry.Offset(width, y * gridSize),
                strokeWidth = 1f
            )
        }

        // Scanning line
        val actualY = (scanlineY / 1000f) * height
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Cyan.copy(alpha = 0.1f), Color.Transparent),
                startY = actualY - 20,
                endY = actualY + 20
            ),
            start = androidx.compose.ui.geometry.Offset(0f, actualY),
            end = androidx.compose.ui.geometry.Offset(width, actualY),
            strokeWidth = 4f
        )
    }
}

@Composable
fun HudCard(title: String, value: String, icon: ImageVector, modifier: Modifier, color: Color) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(14.dp), tint = color)
                Spacer(modifier = Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}

@Composable
fun DashboardButton(
    text: String,
    subText: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text.uppercase(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        }
    }
}
