package com.example.disastermesh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class GuideItem(val title: String, val content: String, val category: String)

@Composable
fun SurvivalGuideScreen() {
    val guides = listOf(
        GuideItem("Earthquake: Indoors", "Drop, Cover, and Hold On. Stay away from windows and heavy furniture.", "Emergency"),
        GuideItem("Earthquake: Outdoors", "Move to an open area away from buildings, trees, and power lines.", "Emergency"),
        GuideItem("Earthquake: Driving", "Pull over to a clear location. Avoid bridges, overpasses, and power lines.", "Emergency"),
        GuideItem("Finding Water", "Collect rainwater, look for transpiration from plants, or boil questionable water for 1 min.", "Water"),
        GuideItem("Basic First Aid", "Control bleeding with direct pressure. Treat for shock by keeping the victim warm and horizontal.", "Medical"),
        GuideItem("Signaling for Help", "Use three of anything (whistles, light flashes, fires) to signal distress internationally.", "Rescue")
    )

    var searchQuery by remember { mutableStateOf("") }
    val filteredGuides = guides.filter { it.title.contains(searchQuery, ignoreCase = true) || it.content.contains(searchQuery, ignoreCase = true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Survival Manual", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search topics...") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(filteredGuides) { guide ->
                GuideCard(guide)
            }
        }
    }
}

@Composable
fun GuideCard(guide: GuideItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = guide.category.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = guide.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = guide.content,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}
