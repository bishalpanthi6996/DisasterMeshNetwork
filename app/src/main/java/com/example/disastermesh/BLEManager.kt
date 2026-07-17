package com.example.disastermesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer

class BLEManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    
    private val advertiser: BluetoothLeAdvertiser? get() = adapter?.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private val MESH_MANUFACTURER_ID = 0xFFFF 
    private val MESH_HEADER = "RESQ".toByteArray(Charsets.UTF_8)

    var onSosDetected: ((String, Double, Double, String, Int) -> Unit)? = null
    private var isScanning = false
    
    private val relayedIds = mutableSetOf<Int>()
    private var nearbyNodeCount = 0

    @SuppressLint("MissingPermission")
    fun startSosBroadcast(sosId: String, lat: Double, lon: Double, triage: String, vCount: Int, isRelay: Boolean = false) {
        val adv = advertiser ?: return
        
        val idHash = if (sosId.contains("-")) {
            sosId.split("-").last().toIntOrNull() ?: sosId.hashCode()
        } else {
            sosId.hashCode()
        }
        
        if (isRelay && relayedIds.contains(idHash)) return
        if (isRelay && nearbyNodeCount <= 1) return

        if (isRelay) relayedIds.add(idHash)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Format: HEADER(4) | ID_HASH(4) | LAT(8) | LON(8) | TRIAGE(1) | V_COUNT(1) = 26 bytes
        val dataBuffer = ByteBuffer.allocate(26)
        dataBuffer.put(MESH_HEADER)
        dataBuffer.putInt(idHash)
        dataBuffer.putDouble(lat)
        dataBuffer.putDouble(lon)
        val triageByte = when(triage) {
            "CRITICAL" -> 3
            "SERIOUS" -> 2
            else -> 1
        }.toByte()
        dataBuffer.put(triageByte)
        dataBuffer.put(vCount.toByte())

        val data = AdvertiseData.Builder()
            .addManufacturerData(MESH_MANUFACTURER_ID, dataBuffer.array())
            .setIncludeDeviceName(false)
            .build()

        adv.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BLEMesh", "Broadcast active for #$idHash with $vCount victims")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun stopSosBroadcast() {
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        relayedIds.clear()
    }
    
    fun updateNearbyNodeCount(count: Int) {
        this.nearbyNodeCount = count
    }

    @SuppressLint("MissingPermission")
    fun startMeshScanning() {
        val scn = scanner ?: return
        if (isScanning) return
        isScanning = true
        
        val filter = ScanFilter.Builder()
            .setManufacturerData(MESH_MANUFACTURER_ID, MESH_HEADER)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        scn.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.scanRecord?.getManufacturerSpecificData(MESH_MANUFACTURER_ID)?.let { data ->
                    try {
                        if (data.size < 26) return@let
                        
                        val buffer = ByteBuffer.wrap(data)
                        val header = ByteArray(4)
                        buffer.get(header)
                        
                        val idHash = buffer.int
                        val lat = buffer.double
                        val lon = buffer.double
                        val triageByte = buffer.get()
                        val vCount = buffer.get().toInt()
                        
                        val triage = when(triageByte.toInt()) {
                            3 -> "CRITICAL"
                            2 -> "SERIOUS"
                            else -> "STABLE"
                        }
                        
                        onSosDetected?.invoke("BEACON-$idHash", lat, lon, triage, vCount)
                    } catch (e: Exception) { }
                }
            }
        })
    }
}
