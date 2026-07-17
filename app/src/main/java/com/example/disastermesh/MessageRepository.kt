package com.example.disastermesh

import kotlinx.coroutines.flow.Flow

class MessageRepository(private val messageDao: MessageDao) {

    val allMessages: Flow<List<MessageEntity>> = messageDao.getAllMessages()

    suspend fun insertMessage(message: MessageEntity) {
        messageDao.insertMessage(message)
    }

    suspend fun exists(msgId: String): Boolean {
        return messageDao.exists(msgId)
    }

    suspend fun getMessageById(msgId: String): MessageEntity? {
        return messageDao.getMessageById(msgId)
    }

    suspend fun getActiveUserSos(): MessageEntity? {
        return messageDao.getActiveUserSos()
    }

    suspend fun updateMessageStatus(msgId: String, status: String) {
        messageDao.updateMessageStatus(msgId, status)
    }

    suspend fun updateSyncStatus(msgId: String, isSynced: Boolean) {
        messageDao.updateSyncStatus(msgId, isSynced)
    }

    suspend fun deleteAllMessages() {
        messageDao.deleteAllMessages()
    }

    suspend fun deleteMessage(msgId: String) {
        messageDao.deleteMessage(msgId)
    }
}
