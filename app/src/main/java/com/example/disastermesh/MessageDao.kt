package com.example.disastermesh

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE messageId = :msgId)")
    suspend fun exists(msgId: String): Boolean

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :msgId LIMIT 1")
    suspend fun getMessageById(msgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sender = 'Me' AND type = 'SOS' AND status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveUserSos(): MessageEntity?

    @Query("UPDATE messages SET status = :status WHERE messageId = :msgId")
    suspend fun updateMessageStatus(msgId: String, status: String)

    @Query("UPDATE messages SET isCloudSynced = :isSynced WHERE messageId = :msgId")
    suspend fun updateSyncStatus(msgId: String, isSynced: Boolean)

    @Query("UPDATE messages SET latitude = :lat, longitude = :lon WHERE messageId = :msgId")
    suspend fun updateMessageLocation(msgId: String, lat: Double, lon: Double)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM messages WHERE messageId = :msgId")
    suspend fun deleteMessage(msgId: String)
}