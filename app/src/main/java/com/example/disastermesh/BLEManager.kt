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

    private var currentAdvertiseCallback: AdvertiseCallback? = null
    private var lastAdvertisedPayload: ByteArray? = null

    // Feature: Hardened Proprietary Rescue Protocol
    private val MESH_MANUFACTURER_ID = 0x5251 // 'RQ' Authenticated ID
    private val MESH_HEADER = "RESQ".toByteArray(Charsets.UTF_8)

    var onSosDetected: ((String, Double, Double, String, Int) -> Unit)? = null
    private var isScanning = false
    
    private val relayedIds = mutableSetOf<Int>()
    private var nearbyNodeCount = 0
    private var isBurstMode = false

    @SuppressLint("MissingPermission")
    fun startSosBroadcast(sosId: String, lat: Double, lon: Double, triage: String, vCount: Int, isRelay: Boolean = false) {
        val adv = advertiser ?: return
        
        val idHash = getBeaconId(sosId)
        
        if (isRelay && relayedIds.contains(idHash)) return
        if (isRelay) relayedIds.add(idHash)

        // Enter Burst Mode for instant discovery if it's a new SOS
        if (!isBurstMode) {
            isBurstMode = true
            restartScanningWithHighPriority()
        }

        // Triage: 1=STABLE, 2=SERIOUS, 3=CRITICAL, 4=RESOLVED/DELETE
        val triageByte = when(triage) {
            "RESOLVED" -> 4
            "CRITICAL" -> 3
            "SERIOUS" -> 2
            else -> 1
        }.toByte()

        val dataBuffer = ByteBuffer.allocate(27)
        dataBuffer.put(MESH_HEADER)
        dataBuffer.putInt(idHash)
        dataBuffer.putDouble(lat)
        dataBuffer.putDouble(lon)
        dataBuffer.put(triageByte)
        dataBuffer.put(vCount.toByte())
        
        val checksum = (idHash % 100 + lat.toInt() % 100 + lon.toInt() % 100 + triageByte + vCount).toByte()
        dataBuffer.put(checksum)

        val payload = dataBuffer.array()
        
        // Anti-Spam: Don't restart advertising if the payload is exactly the same
        if (lastAdvertisedPayload?.contentEquals(payload) == true) {
            return
        }

        // Stop existing advertisement before starting new one to prevent accumulation
        currentAdvertiseCallback?.let {
            adv.stopAdvertising(it)
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // Fast advertising
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // High range
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(MESH_MANUFACTURER_ID, payload)
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BLEMesh", "Broadcast Active [#$idHash] Mode: $triage")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("BLEMesh", "Broadcast Failed: $errorCode")
            }
        }
        
        currentAdvertiseCallback = callback
        lastAdvertisedPayload = payload
        adv.startAdvertising(settings, data, callback)
    }

    private fun getBeaconId(sosId: String): Int {
        val clean = sosId.removePrefix("BEACON-")
        return clean.toIntOrNull() ?: Math.abs(sosId.hashCode() % 100000)
    }

    @SuppressLint("MissingPermission")
    fun stopSosBroadcast() {
        currentAdvertiseCallback?.let {
            advertiser?.stopAdvertising(it)
            currentAdvertiseCallback = null
        }
        lastAdvertisedPayload = null
        relayedIds.clear()
        if (isBurstMode) {
            isBurstMode = false
            restartScanningWithHighPriority()
        }
    }
    
    fun updateNearbyNodeCount(count: Int) {
        this.nearbyNodeCount = count
    }

    private fun restartScanningWithHighPriority() {
        if (isScanning) {
            stopScanningInternal()
            startMeshScanning()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningInternal() {
        scanner?.stopScan(mScanCallback)
        isScanning = false
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
            // In burst mode (active SOS), use LOW_LATENCY (continuous scanning)
            .setScanMode(if (isBurstMode) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        scn.startScan(listOf(filter), settings, mScanCallback)
    }

    private val mScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.scanRecord?.getManufacturerSpecificData(MESH_MANUFACTURER_ID)?.let { data ->
                try {
                    if (data.size < 27) return@let
                    
                    val buffer = ByteBuffer.wrap(data)
                    val headerBytes = ByteArray(4)
                    buffer.get(headerBytes)
                    
                    if (!headerBytes.contentEquals(MESH_HEADER)) return@let
                    
                    val idHash = buffer.int
                    val lat = buffer.double
                    val lon = buffer.double
                    val triageByte = buffer.get()
                    val vCount = buffer.get().toInt()
                    val receivedChecksum = buffer.get()
                    
                    val calculatedChecksum = (idHash % 100 + lat.toInt() % 100 + lon.toInt() % 100 + triageByte + vCount).toByte()
                    if (receivedChecksum != calculatedChecksum) return@let
                    
                    if (lat == 0.0 || lon == 0.0 || lat < -90 || lat > 90) return@let
                    
                    val triage = when(triageByte.toInt()) {
                        4 -> "RESOLVED"
                        3 -> "CRITICAL"
                        2 -> "SERIOUS"
                        else -> "STABLE"
                    }
                    
                    onSosDetected?.invoke("BEACON-$idHash", lat, lon, triage, vCount)
                } catch (e: Exception) { }
            }
        }
    }
}
