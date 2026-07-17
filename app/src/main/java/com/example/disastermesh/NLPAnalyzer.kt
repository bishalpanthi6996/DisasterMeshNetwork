package com.example.disastermesh

import java.util.Locale
import kotlin.math.max

object NLPAnalyzer {

    private val CRITICAL_KEYWORDS = listOf("bleeding", "unconscious", "breath", "heart", "trapped", "dying", "crushed", "fire", "drowning")
    private val SERIOUS_KEYWORDS = listOf("broken", "fracture", "pain", "wound", "water", "food", "starving", "sick", "fever", "stuck")

    /**
     * Uses Natural Language Processing (Keyword Analysis) to prioritize victims.
     */
    fun analyzeTriage(message: String): String {
        val lowerMessage = message.lowercase(Locale.getDefault())
        
        return when {
            CRITICAL_KEYWORDS.any { lowerMessage.contains(it) } -> "CRITICAL"
            SERIOUS_KEYWORDS.any { lowerMessage.contains(it) } -> "SERIOUS"
            else -> "STABLE"
        }
    }

    /**
     * AI-Based Victim Prioritization (Feature 1)
     * Calculates a rank based on multiple disaster factors.
     */
    fun calculateEmergencyRank(
        triage: String,
        victimCount: Int,
        batteryLevel: Int,
        timestamp: Long
    ): Int {
        var score = 0
        
        // 1. Severity of injuries
        score += when (triage) {
            "CRITICAL" -> 500
            "SERIOUS" -> 200
            else -> 0
        }
        
        // 2. Number of people affected
        score += victimCount * 50
        
        // 3. Battery level (Lower battery = Higher priority for forwarding/rescue)
        if (batteryLevel != -1) {
            score += (100 - batteryLevel) * 2
        }
        
        // 4. Time since SOS (Older messages get higher priority to prevent starvation)
        val minutesElapsed = (System.currentTimeMillis() - timestamp) / 60000
        score += (minutesElapsed * 5).toInt()
        
        return score
    }

    /**
     * Group victims based on proximity (Feature 5 - Simple version)
     */
    fun isNearby(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val dist = Math.sqrt(Math.pow(lat1 - lat2, 2.0) + Math.pow(lon1 - lon2, 2.0))
        return dist < 0.001 // Approx 100 meters
    }
}
