package com.example.disastermesh

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceManager {
    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    
    private val gainFactor = 4.0f 

    @SuppressLint("MissingPermission")
    fun startRecording(context: Context, outputFile: File, onDataReady: (ByteArray, Int) -> Unit) {
        if (isRecording) return

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        if (minBufSize <= 0) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfigIn,
                audioFormat,
                minBufSize.coerceAtLeast(1024 * 4)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                isRecording = false
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(minBufSize)
                try {
                    outputFile.outputStream().use { fos ->
                        while (isRecording && isActive) {
                            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (read > 0) {
                                val chunk = buffer.copyOf(read)
                                applyGain(chunk)
                                fos.write(chunk)
                                onDataReady(chunk, chunk.size)
                            }
                        }
                        fos.flush()
                    }
                } catch (e: Exception) {
                    Log.e("VoiceManager", "Recording error", e)
                }
            }
        } catch (e: Exception) {
            isRecording = false
        }
    }

    private fun applyGain(data: ByteArray) {
        if (data.size < 2) return
        val shorts = ShortArray(data.size / 2)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().get(shorts)
        for (i in shorts.indices) {
            val boosted = (shorts[i] * gainFactor).toInt()
            shorts[i] = boosted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        bb.rewind()
        bb.asShortBuffer().put(shorts)
    }

    suspend fun stopRecording() {
        isRecording = false
        recordingJob?.join()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }

    fun playAudioFile(path: String, onInfo: (String) -> Unit = {}) {
        stopPlayback()
        
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(path)
                if (!file.exists() || file.length() == 0L) {
                    withContext(Dispatchers.Main) { onInfo("Empty File") }
                    return@launch
                }
                
                val minTrackBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfigOut)
                            .build()
                    )
                    .setBufferSizeInBytes(minTrackBufSize.coerceAtLeast(1024 * 32))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) { 
                        audioTrack = track
                        onInfo("Playing...") 
                    }
                    track.play()
                    
                    file.inputStream().use { fis ->
                        val buffer = ByteArray(minTrackBufSize)
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1 && isActive) {
                            track.write(buffer, 0, read)
                        }
                    }
                    delay(800)
                } else {
                    withContext(Dispatchers.Main) { onInfo("Hardware Error") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onInfo("Error") }
            } finally {
                stopPlayback()
            }
        }
    }

    fun playChunk(data: ByteArray) {
        try {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfigOut)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize.coerceAtLeast(1024 * 64))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()
            }
            audioTrack?.write(data, 0, data.size)
        } catch (e: Exception) {}
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
    }

    fun release() {
        stopPlayback()
    }
}
