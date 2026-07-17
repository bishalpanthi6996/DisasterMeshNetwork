package com.example.disastermesh

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import android.graphics.Color as AndroidColor
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MapScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val allMessages by viewModel.allMessages.collectAsState()
    val messages = remember(allMessages) {
        allMessages.filter { it.status == "ACTIVE" }
    }
    
    Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
    // Increase cache size for better offline performance
    Configuration.getInstance().cacheMapTileCount = 100
    Configuration.getInstance().cacheMapTileOvershoot = 20

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Enable offline use
            setUseDataConnection(true) // Set to false to force offline if tiles are pre-loaded
            controller.setZoom(15.0)
        }
    }

    val locationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            enableMyLocation()
        }
    }

    DisposableEffect(mapView) {
        mapView.overlays.add(locationOverlay)
        onDispose {
            locationOverlay.disableMyLocation()
            mapView.onDetach()
        }
    }

    // Handle "Jump to Location" from Chat
    LaunchedEffect(viewModel.mapTargetLocation) {
        viewModel.mapTargetLocation?.let { target ->
            mapView.controller.animateTo(GeoPoint(target.first, target.second))
            mapView.controller.setZoom(18.0)
            // Clear the target so it doesn't jump again on rotation
            viewModel.mapTargetLocation = null
        }
    }

    // Update Markers, Hazard Zones & Rescue Planning
    LaunchedEffect(messages, viewModel.activeHazardZone) {
        val currentMarkers = mapView.overlays.filterIsInstance<Marker>()
        val currentPolygons = mapView.overlays.filterIsInstance<Polygon>()
        val currentLines = mapView.overlays.filterIsInstance<Polyline>()
        mapView.overlays.removeAll(currentMarkers)
        mapView.overlays.removeAll(currentPolygons)
        mapView.overlays.removeAll(currentLines)

        // Geometrical Landscape Analysis: Render Hazard Zones
        viewModel.activeHazardZone?.let { zone ->
            val circle = Polygon.pointsAsCircle(GeoPoint(zone.first, zone.second), viewModel.hazardRadius)
            val polygon = Polygon(mapView)
            polygon.points = circle
            polygon.fillPaint.color = AndroidColor.argb(50, 255, 0, 0)
            polygon.outlinePaint.color = AndroidColor.RED
            polygon.outlinePaint.strokeWidth = 2f
            polygon.title = "HAZARD ZONE: ${viewModel.currentEmergencyMode}"
            mapView.overlays.add(polygon)
        }

        // Feature 5: AI Rescue Planning (Route Suggestion)
        val sosMessages = messages.filter { it.type == "SOS" && it.latitude != null && it.longitude != null }
            .sortedByDescending { it.priority } // Priority-based rescue
        
        if (sosMessages.isNotEmpty()) {
            val route = Polyline(mapView)
            val points = mutableListOf<GeoPoint>()
            // Start from my location if available
            locationOverlay.myLocation?.let { points.add(it) }
            
            sosMessages.take(3).forEach { // Suggest route for top 3 urgent victims
                points.add(GeoPoint(it.latitude!!, it.longitude!!))
            }
            
            route.setPoints(points)
            route.outlinePaint.color = AndroidColor.BLUE
            route.outlinePaint.strokeWidth = 5f
            mapView.overlays.add(route)
        }

        // Get the latest location for each sender
        val latestLocations = messages.filter { it.latitude != null && it.longitude != null }
            .sortedByDescending { it.timestamp }
            .distinctBy { it.senderName + it.sender }

        latestLocations.forEach { msg ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(msg.latitude!!, msg.longitude!!)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            
            val senderLabel = if (msg.sender == "Me") "You" else msg.senderName
            
            when (msg.type) {
                "SOS" -> {
                    marker.title = "🚨 [PRIORITY ${msg.priority}] $senderLabel"
                    marker.snippet = "Status: ${msg.triageLevel}\nVictims: ${msg.victimCount}\nBattery: ${msg.senderBattery}%\nType: ${msg.hazardType}"
                    // Custom marker icon logic could go here
                }
                "RESOURCE" -> {
                    marker.title = "🏥 RESOURCE: ${msg.message}"
                }
                else -> {
                    marker.title = "Survivor: $senderLabel"
                }
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Feature 9: Volunteer Dashboard Overlay
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .width(200.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shadowElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Volunteer Dashboard", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val sosCount = messages.count { it.type == "SOS" }
                val criticalCount = messages.count { it.triageLevel == "CRITICAL" }
                Text("Active SOS: $sosCount", style = MaterialTheme.typography.bodySmall)
                Text("Critical: $criticalCount", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                
                if (sosCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("AI Route Active", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = { 
                    locationOverlay.myLocation?.let { mapView.controller.animateTo(it) }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.MyLocation, "My Location")
            }

            FloatingActionButton(
                onClick = { 
                    val lastFriend = messages.firstOrNull { it.sender != "Me" && it.latitude != null }
                    lastFriend?.let {
                        mapView.controller.animateTo(GeoPoint(it.latitude!!, it.longitude!!))
                        mapView.controller.setZoom(18.0)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.People, "Find Survivors")
            }
        }
    }
}
