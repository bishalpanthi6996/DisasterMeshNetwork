package com.example.disastermesh

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(

    @PrimaryKey
    val messageId: String,

    val sender: String,

    val senderName: String = "Unknown",

    val message: String,

    val type: String = "CHAT", // CHAT, SOS, RESOURCE, ALERT, VOICE

    val timestamp: Long = System.currentTimeMillis(),

    val priority: Int = 1,

    val delivered: Boolean = false,

    val latitude: Double? = null,

    val longitude: Double? = null,

    val isVerified: Boolean = false, // True for Government/Official messages

    val triageLevel: String = "STABLE", // CRITICAL, SERIOUS, STABLE

    val audioPath: String? = null, // Path to saved voice message file
    val audioDuration: Int = 0, // Duration in seconds

    // Rich SOS Data
    val victimCount: Int = 1,
    val medicalConditions: String? = null,
    val senderBattery: Int = -1,
    val ttl: Int = 5, // Time to live (mesh hops)
    val hazardType: String = "GENERAL", // EARTHQUAKE, FLOOD, etc.
    
    // Status tracking for SOS (Feature: Duplicate Prevention)
    val status: String = "ACTIVE", // ACTIVE, SOLVED, CANCELLED

    // Hybrid Mesh Logic (Feature: Mobile Network Integration)
    val isCloudSynced: Boolean = false
)
