package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.model.BleConnectionStatus
import com.example.model.DiscoveredDevice
import com.example.model.SeaMeshPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onConnectionStatusChanged: (BleConnectionStatus) -> Unit,
    private val onPacketReceived: (SeaMeshPacket) -> Unit,
    private val onDiscoveredDevicesChanged: (List<DiscoveredDevice>) -> Unit
) {
    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID: UUID = UUID.fromString("6e5a0001-5ea3-4e5a-9c1d-2b7f0a4c8e10")
        val GPS_CHAR_UUID: UUID = UUID.fromString("6e5a0002-5ea3-4e5a-9c1d-2b7f0a4c8e10")
        val CONTROL_CHAR_UUID: UUID = UUID.fromString("6e5a0003-5ea3-4e5a-9c1d-2b7f0a4c8e10")
        val PACKETS_CHAR_UUID: UUID = UUID.fromString("6e5a0004-5ea3-4e5a-9c1d-2b7f0a4c8e10")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetMac: String? = null
    private var isAutoReconnectActive: Boolean = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var reconnectJob: Job? = null
    private var mtuTimeoutJob: Job? = null
    private var writeTimeoutJob: Job? = null

    // GATT Characteristics cache
    private var gpsCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var packetsCharacteristic: BluetoothGattCharacteristic? = null

    // Write queue to ensure sequential WRITE_TYPE_DEFAULT operations
    private sealed class WriteOp {
        data class CharWrite(
            val characteristic: BluetoothGattCharacteristic,
            val data: ByteArray
        ) : WriteOp()

        data class DescWrite(
            val descriptor: BluetoothGattDescriptor,
            val data: ByteArray
        ) : WriteOp()
    }

    private val writeQueue = ArrayDeque<WriteOp>()
    private var isWriting = false
    private val queueLock = Any()

    // Discovery / Scan
    private val discoveredMap = LinkedHashMap<String, DiscoveredDevice>()
    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    var onConnectionReady: (() -> Unit)? = null

    fun startScan() {
        if (!isBluetoothEnabled) {
            Log.w(TAG, "Bluetooth not enabled, cannot scan")
            return
        }

        stopScan()
        discoveredMap.clear()
        onDiscoveredDevicesChanged(emptyList())

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null")
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { handleScanResult(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code $errorCode")
                isScanning = false
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            Log.d(TAG, "Started BLE scan")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}")
            isScanning = false
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = device.name ?: result.scanRecord?.deviceName ?: ""
        // Check if device name starts with "SeaMesh-"
        if (name.startsWith("SeaMesh-", ignoreCase = true)) {
            val address = device.address
            val rssi = result.rssi
            synchronized(discoveredMap) {
                discoveredMap[address] = DiscoveredDevice(name = name, address = address, rssi = rssi)
                onDiscoveredDevicesChanged(discoveredMap.values.toList())
            }
        }
    }

    fun stopScan() {
        if (isScanning && scanCallback != null) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan: ${e.message}")
            }
            scanCallback = null
            isScanning = false
            Log.d(TAG, "Stopped BLE scan")
        }
    }

    fun connect(macAddress: String) {
        targetMac = macAddress
        isAutoReconnectActive = true
        reconnectJob?.cancel()

        if (!isBluetoothEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            onConnectionStatusChanged(BleConnectionStatus.DISCONNECTED)
            return
        }

        disconnectAndClose()

        val adapter = bluetoothAdapter
        if (adapter == null) {
            onConnectionStatusChanged(BleConnectionStatus.NOT_FOUND)
            return
        }

        try {
            val device = adapter.getRemoteDevice(macAddress)
            Log.d(TAG, "Connecting to ${device.name ?: macAddress} ($macAddress)...")
            onConnectionStatusChanged(BleConnectionStatus.CONNECTING)

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating connect to $macAddress: ${e.message}")
            onConnectionStatusChanged(BleConnectionStatus.NOT_FOUND)
            scheduleReconnect()
        }
    }

    fun disconnect() {
        isAutoReconnectActive = false
        reconnectJob?.cancel()
        disconnectAndClose()
        onConnectionStatusChanged(BleConnectionStatus.DISCONNECTED)
    }

    private fun disconnectAndClose() {
        synchronized(queueLock) {
            writeQueue.clear()
            isWriting = false
        }
        writeTimeoutJob?.cancel()
        mtuTimeoutJob?.cancel()

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT: ${e.message}")
        } finally {
            bluetoothGatt = null
            gpsCharacteristic = null
            controlCharacteristic = null
            packetsCharacteristic = null
        }
    }

    private fun scheduleReconnect() {
        if (!isAutoReconnectActive || targetMac == null) return

        reconnectJob?.cancel()
        reconnectJob = coroutineScope.launch {
            Log.d(TAG, "Waiting 5 seconds before reconnecting to $targetMac...")
            delay(5000L)
            targetMac?.let { mac ->
                if (isAutoReconnectActive) {
                    Log.d(TAG, "Reconnecting to $mac now...")
                    connect(mac)
                }
            }
        }
    }

    // Public write API: send latest GPS position: "<lat>,<lon>,<accuracy_m>,<fix_unix_time_s>"
    fun writeGps(lat: Double, lon: Double, accuracy: Float, timeSeconds: Long): Boolean {
        val char = gpsCharacteristic ?: return false
        val payload = String.format(java.util.Locale.US, "%.6f,%.6f,%.1f,%d", lat, lon, accuracy, timeSeconds)
        return enqueueWrite(WriteOp.CharWrite(char, payload.toByteArray(Charsets.UTF_8)))
    }

    // Public write API: send internet status "NET,1" or "NET,0"
    fun writeInternetStatus(isOnline: Boolean): Boolean {
        val char = controlCharacteristic ?: return false
        val payload = if (isOnline) "NET,1" else "NET,0"
        return enqueueWrite(WriteOp.CharWrite(char, payload.toByteArray(Charsets.UTF_8)))
    }

    // Public write API: send ACK "<origin>,<msgId>"
    fun writeAck(boat: String, msgId: Long): Boolean {
        val char = controlCharacteristic ?: return false
        val payload = "ACK,$boat,$msgId"
        return enqueueWrite(WriteOp.CharWrite(char, payload.toByteArray(Charsets.UTF_8)))
    }

    private fun enqueueWrite(op: WriteOp): Boolean {
        synchronized(queueLock) {
            writeQueue.add(op)
            if (!isWriting) {
                processNextWrite()
            }
        }
        return true
    }

    private fun processNextWrite() {
        synchronized(queueLock) {
            val op = writeQueue.peekFirst()
            if (op == null) {
                isWriting = false
                writeTimeoutJob?.cancel()
                return
            }

            val gatt = bluetoothGatt
            if (gatt == null) {
                writeQueue.clear()
                isWriting = false
                writeTimeoutJob?.cancel()
                return
            }

            isWriting = true

            // Set watchdog timeout for write operation (2500ms)
            writeTimeoutJob?.cancel()
            writeTimeoutJob = coroutineScope.launch {
                delay(2500L)
                synchronized(queueLock) {
                    if (isWriting && writeQueue.isNotEmpty()) {
                        Log.w(TAG, "Write operation timed out; advancing queue")
                        writeQueue.pollFirst()
                        processNextWrite()
                    }
                }
            }

            try {
                when (op) {
                    is WriteOp.CharWrite -> {
                        val characteristic = op.characteristic
                        val data = op.data
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val res = gatt.writeCharacteristic(
                                characteristic,
                                data,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            )
                            if (res != 0) {
                                Log.w(TAG, "writeCharacteristic returned error status: $res")
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            @Suppress("DEPRECATION")
                            characteristic.value = data
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }
                    }
                    is WriteOp.DescWrite -> {
                        val descriptor = op.descriptor
                        val data = op.data
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, data)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = data
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during GATT write: ${e.message}")
                writeQueue.pollFirst()
                processNextWrite()
            }
        }
    }

    private fun onWriteFinished() {
        synchronized(queueLock) {
            writeTimeoutJob?.cancel()
            if (writeQueue.isNotEmpty()) {
                writeQueue.pollFirst()
            }
            processNextWrite()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Connection failed with GATT status $status")
                disconnectAndClose()
                onConnectionStatusChanged(BleConnectionStatus.NOT_FOUND)
                scheduleReconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server. Requesting MTU 185...")
                onConnectionStatusChanged(BleConnectionStatus.CONNECTING)

                // Request MTU 185 after connecting
                val mtuRequested = gatt.requestMtu(185)
                Log.d(TAG, "requestMtu(185) returned: $mtuRequested")

                // Fallback in case onMtuChanged is not called
                mtuTimeoutJob?.cancel()
                mtuTimeoutJob = coroutineScope.launch {
                    delay(1200L)
                    Log.d(TAG, "MTU callback fallback: discovering services...")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server")
                disconnectAndClose()
                onConnectionStatusChanged(BleConnectionStatus.DISCONNECTED)
                scheduleReconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            mtuTimeoutJob?.cancel()
            // Discover services after MTU change
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "SeaMesh GATT service ($SERVICE_UUID) not found on device")
                return
            }

            gpsCharacteristic = service.getCharacteristic(GPS_CHAR_UUID)
            controlCharacteristic = service.getCharacteristic(CONTROL_CHAR_UUID)
            packetsCharacteristic = service.getCharacteristic(PACKETS_CHAR_UUID)

            // Enable notifications on PACKETS by writing the CCCD descriptor (0x2902)
            packetsCharacteristic?.let { packetsChar ->
                gatt.setCharacteristicNotification(packetsChar, true)
                val cccd = packetsChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    Log.d(TAG, "Writing CCCD descriptor to enable notifications on PACKETS...")
                    enqueueWrite(WriteOp.DescWrite(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                } else {
                    Log.e(TAG, "CCCD descriptor (0x2902) not found on PACKETS characteristic")
                }
            }

            onConnectionStatusChanged(BleConnectionStatus.CONNECTED)
            onConnectionReady?.invoke()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite: ${characteristic.uuid}, status=$status")
            onWriteFinished()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite: ${descriptor.uuid}, status=$status")
            onWriteFinished()
        }

        @Suppress("DEPRECATION")
        @Deprecated("Used for Android API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleReceivedCharacteristic(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleReceivedCharacteristic(characteristic.uuid, value)
        }
    }

    private fun handleReceivedCharacteristic(uuid: UUID, data: ByteArray?) {
        if (uuid == PACKETS_CHAR_UUID && data != null) {
            val text = String(data, Charsets.UTF_8).trim()
            Log.d(TAG, "Received PACKETS notification: $text")
            val packet = SeaMeshPacket.parse(text)
            if (packet != null) {
                onPacketReceived(packet)
            } else {
                Log.w(TAG, "Ignored invalid packet format (must have exactly 11 fields): $text")
            }
        }
    }
}
