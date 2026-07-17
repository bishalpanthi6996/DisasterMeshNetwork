package com.example.disastermesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*

class BluetoothConnectionManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val serviceUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val serviceName = "DisasterMeshService"

    var onConnected: ((BluetoothSocket) -> Unit)? = null
    
    private var serverJob: Job? = null
    private var isListening = false

    @SuppressLint("MissingPermission")
    fun listen() {
        if (isListening) return
        isListening = true
        
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                var serverSocket: BluetoothServerSocket? = null
                try {
                    serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid)
                    Log.d("MeshNetwork", "Server socket listening...")
                    
                    val socket = serverSocket?.accept()
                    serverSocket?.close() // Close server socket after accepting one connection for stability

                    if (socket != null) {
                        withContext(Dispatchers.Main) {
                            onConnected?.invoke(socket)
                        }
                    }
                } catch (e: IOException) {
                    Log.e("MeshNetwork", "Server socket error: ${e.message}")
                    delay(2000) // Retry after delay
                } finally {
                    try { serverSocket?.close() } catch (e: Exception) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            var socket: BluetoothSocket? = null
            try {
                // Cancel discovery because it slows down the connection
                bluetoothAdapter?.cancelDiscovery()
                
                socket = device?.createRfcommSocketToServiceRecord(serviceUuid)
                socket?.connect()

                withContext(Dispatchers.Main) {
                    socket?.let { onConnected?.invoke(it) }
                }
            } catch (e: IOException) {
                Log.e("MeshNetwork", "Connection failed: ${e.message}")
                try { socket?.close() } catch (e2: Exception) {}
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        isListening = false
    }
}
