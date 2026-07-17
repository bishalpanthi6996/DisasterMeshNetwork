package com.example.disastermesh

import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream

class BluetoothChatManager(
    private val socket: BluetoothSocket
) {
    private val inputStream: InputStream = socket.inputStream
    private val outputStream: OutputStream = socket.outputStream
    private var listeningJob: Job? = null

    // Protocol Constants
    private val TYPE_TEXT = 0x01.toByte()
    private val TYPE_AUDIO_CHUNK = 0x02.toByte()
    private val TYPE_AUDIO_START = 0x03.toByte()
    private val TYPE_AUDIO_END = 0x04.toByte()

    fun sendMessage(message: String) {
        sendPacket(TYPE_TEXT, message.toByteArray())
    }

    fun startAudioStream(metadata: String = "") {
        sendPacket(TYPE_AUDIO_START, metadata.toByteArray())
    }

    fun sendAudioChunk(audioData: ByteArray, size: Int) {
        sendPacket(TYPE_AUDIO_CHUNK, audioData.copyOf(size))
    }

    fun stopAudioStream() {
        sendPacket(TYPE_AUDIO_END, ByteArray(0))
    }

    private fun sendPacket(type: Byte, data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val packet = ByteArray(data.size + 5)
                packet[0] = type
                val len = data.size
                packet[1] = (len shr 24).toByte()
                packet[2] = (len shr 16).toByte()
                packet[3] = (len shr 8).toByte()
                packet[4] = len.toByte()
                System.arraycopy(data, 0, packet, 5, data.size)
                
                outputStream.write(packet)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e("MeshChat", "Packet send error: ${e.message}")
            }
        }
    }

    fun startListening(
        onMessageReceived: (String) -> Unit,
        onAudioStart: (String) -> Unit,
        onAudioChunk: (ByteArray) -> Unit,
        onAudioEnd: () -> Unit
    ) {
        listeningJob?.cancel()
        listeningJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val header = ByteArray(5)
                while (isActive) {
                    var totalRead = 0
                    while (totalRead < 5) {
                        val read = inputStream.read(header, totalRead, 5 - totalRead)
                        if (read == -1) return@launch
                        totalRead += read
                    }

                    val type = header[0]
                    val length = ((header[1].toInt() and 0xFF) shl 24) or
                                 ((header[2].toInt() and 0xFF) shl 16) or
                                 ((header[3].toInt() and 0xFF) shl 8) or
                                 (header[4].toInt() and 0xFF)

                    val body = if (length > 0) ByteArray(length) else ByteArray(0)
                    var bodyRead = 0
                    while (bodyRead < length) {
                        val read = inputStream.read(body, bodyRead, length - bodyRead)
                        if (read == -1) return@launch
                        bodyRead += read
                    }

                    withContext(Dispatchers.Main) {
                        when (type) {
                            TYPE_TEXT -> onMessageReceived(String(body))
                            TYPE_AUDIO_START -> onAudioStart(String(body))
                            TYPE_AUDIO_CHUNK -> onAudioChunk(body)
                            TYPE_AUDIO_END -> onAudioEnd()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MeshChat", "Listen error: ${e.message}")
            }
        }
    }

    fun close() {
        listeningJob?.cancel()
        try { socket.close() } catch (e: Exception) {}
    }
}
