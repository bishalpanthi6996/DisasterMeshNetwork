package com.example.disastermesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

class BluetoothManagerHelper(
    private val context: Context
) {

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager.adapter

    val nearbyDevices = mutableStateListOf<Pair<String, String>>()

    private var receiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            Log.d("BluetoothDiscovery", "Broadcast received: ${intent?.action}")

            when (intent?.action) {

                BluetoothDevice.ACTION_FOUND -> {

                    Log.d("BluetoothDiscovery", "FOUND EVENT RECEIVED")

                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                    if (device != null) {

                        val name = try {
                            device.name ?: "Unknown Device"
                        } catch (e: SecurityException) {
                            "Unknown Device"
                        }

                        val address = device.address

                        addDevice(name, address)

                        Log.d("BluetoothDiscovery", "Found: $name ($address)")
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("BluetoothDiscovery", "Discovery Finished")
                }
            }
        }
    }

    fun startDiscovery() {

        clearDevices()

        val adapter = bluetoothAdapter ?: return

        if (!adapter.isEnabled) {
            Log.d("BluetoothDiscovery", "Bluetooth is OFF")
            return
        }

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(bluetoothReceiver, filter)
            }
            receiverRegistered = true
        }

        val started = adapter.startDiscovery()

        Log.d("BluetoothDiscovery", "Discovery Started: $started")
    }

    fun stopDiscovery() {

        bluetoothAdapter?.cancelDiscovery()

        if (receiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
                receiverRegistered = false
            } catch (_: Exception) {
            }
        }
    }

    fun addDevice(name: String, address: String) {
        val device = name to address

        if (!nearbyDevices.contains(device)) {
            nearbyDevices.add(device)
        }
    }

    fun clearDevices() {
        nearbyDevices.clear()
    }
}