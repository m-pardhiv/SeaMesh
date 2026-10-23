package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.data.SeaMeshPreferences
import com.example.model.BleConnectionStatus
import com.example.model.DiscoveredDevice
import com.example.service.SeaMeshService
import com.example.state.SeaMeshStateHolder
import com.example.ui.DevicePickerDialog
import com.example.ui.SeaMeshScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var prefs: SeaMeshPreferences
    private var isScannerActive = false
    private var scanCallback: ScanCallback? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        Log.d(TAG, "Permissions result: allGranted=$allGranted, results=$results")
        updateHardwareAndPermissionsState()

        if (allGranted && prefs.isActive && !SeaMeshService.isRunning) {
            startSeaMeshService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = SeaMeshPreferences(this)

        // Initialize StateHolder with stored preferences
        SeaMeshStateHolder.updateActive(prefs.isActive)
        SeaMeshStateHolder.updateSavedDevice(prefs.savedDeviceName, prefs.savedDeviceAddress)
        SeaMeshStateHolder.updateSettings(prefs.serverUrl, prefs.apiKey)

        // Initial permissions & hardware status check
        updateHardwareAndPermissionsState()

        // If app was left active and we have permissions, resume service
        if (prefs.isActive && hasRequiredPermissions()) {
            startSeaMeshService()
        }

        setContent {
            MyApplicationTheme {
                val uiState by SeaMeshStateHolder.uiState.collectAsState()
                var hasPerms by remember { mutableStateOf(hasRequiredPermissions()) }
                var showDevicePicker by remember { mutableStateOf(false) }

                // Auto-show device picker on first launch if no device is configured yet
                var hasCheckedFirstRun by remember { mutableStateOf(false) }
                if (!hasCheckedFirstRun) {
                    hasCheckedFirstRun = true
                    if (uiState.savedDeviceAddress == null && hasPerms) {
                        showDevicePicker = true
                        startBleDeviceScan()
                    }
                }

                SeaMeshScreen(
                    uiState = uiState,
                    hasPermissions = hasPerms,
                    onRequestPermissions = {
                        requestRequiredPermissions()
                    },
                    onToggleActive = { active ->
                        prefs.isActive = active
                        SeaMeshStateHolder.updateActive(active)
                        if (active) {
                            if (hasRequiredPermissions()) {
                                startSeaMeshService()
                            } else {
                                requestRequiredPermissions()
                            }
                        } else {
                            stopSeaMeshService()
                        }
                    },
                    onChangeDeviceClick = {
                        showDevicePicker = true
                        startBleDeviceScan()
                    },
                    onOpenBluetoothSettings = {
                        try {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot open Bluetooth settings: ${e.message}")
                        }
                    },
                    onOpenGpsSettings = {
                        try {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot open Location settings: ${e.message}")
                        }
                    },
                    onSaveSettings = { url, key ->
                        prefs.serverUrl = url
                        prefs.apiKey = key
                        SeaMeshStateHolder.updateSettings(url, key)
                    }
                )

                if (showDevicePicker) {
                    DevicePickerDialog(
                        isScanning = uiState.isScanning,
                        devices = uiState.discoveredDevices,
                        onDeviceSelected = { device ->
                            Log.i(TAG, "Selected device: ${device.name} (${device.address})")
                            prefs.savedDeviceAddress = device.address
                            prefs.savedDeviceName = device.name
                            SeaMeshStateHolder.updateSavedDevice(device.name, device.address)

                            stopBleDeviceScan()
                            showDevicePicker = false

                            // If service is running, instruct it to connect to this device
                            if (SeaMeshService.isRunning) {
                                val intent = Intent(this@MainActivity, SeaMeshService::class.java).apply {
                                    action = SeaMeshService.ACTION_CONNECT_DEVICE
                                    putExtra(SeaMeshService.EXTRA_DEVICE_ADDRESS, device.address)
                                    putExtra(SeaMeshService.EXTRA_DEVICE_NAME, device.name)
                                }
                                startService(intent)
                            } else if (prefs.isActive && hasRequiredPermissions()) {
                                startSeaMeshService()
                            }
                        },
                        onStartScan = {
                            startBleDeviceScan()
                        },
                        onDismiss = {
                            stopBleDeviceScan()
                            showDevicePicker = false
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateHardwareAndPermissionsState()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleDeviceScan()
    }

    private fun startSeaMeshService() {
        val intent = Intent(this, SeaMeshService::class.java).apply {
            action = SeaMeshService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSeaMeshService() {
        val intent = Intent(this, SeaMeshService::class.java).apply {
            action = SeaMeshService.ACTION_STOP
        }
        startService(intent)
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val bluetoothScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val bluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return fineLocation && coarseLocation && bluetoothScan && bluetoothConnect && notifications
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updateHardwareAndPermissionsState() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btEnabled = btManager?.adapter?.isEnabled == true

        val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gpsEnabled = locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

        SeaMeshStateHolder.updateHardwareStatus(
            bluetoothEnabled = btEnabled,
            gpsEnabled = gpsEnabled
        )
    }

    @SuppressLint("MissingPermission")
    private fun startBleDeviceScan() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }

        if (SeaMeshService.isRunning) {
            // Forward scan request to running service
            val intent = Intent(this, SeaMeshService::class.java).apply {
                action = SeaMeshService.ACTION_START_SCAN
            }
            startService(intent)
            return
        }

        // Direct scan when service is idle
        stopBleDeviceScan()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = btManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner is not available")
            return
        }

        val discoveredMap = LinkedHashMap<String, DiscoveredDevice>()
        SeaMeshStateHolder.updateDiscoveredDevices(emptyList())
        SeaMeshStateHolder.updateScanning(true)
        isScannerActive = true

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { res ->
                    val dev = res.device ?: return
                    val name = dev.name ?: res.scanRecord?.deviceName ?: ""
                    if (name.startsWith("SeaMesh-", ignoreCase = true)) {
                        synchronized(discoveredMap) {
                            discoveredMap[dev.address] = DiscoveredDevice(
                                name = name,
                                address = dev.address,
                                rssi = res.rssi
                            )
                            SeaMeshStateHolder.updateDiscoveredDevices(discoveredMap.values.toList())
                        }
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { res ->
                    val dev = res.device ?: return
                    val name = dev.name ?: res.scanRecord?.deviceName ?: ""
                    if (name.startsWith("SeaMesh-", ignoreCase = true)) {
                        synchronized(discoveredMap) {
                            discoveredMap[dev.address] = DiscoveredDevice(
                                name = name,
                                address = dev.address,
                                rssi = res.rssi
                            )
                            SeaMeshStateHolder.updateDiscoveredDevices(discoveredMap.values.toList())
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "MainActivity BLE scan failed: $errorCode")
                isScannerActive = false
                SeaMeshStateHolder.updateScanning(false)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            Log.d(TAG, "MainActivity started BLE scan")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}")
            isScannerActive = false
            SeaMeshStateHolder.updateScanning(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleDeviceScan() {
        if (SeaMeshService.isRunning) {
            val intent = Intent(this, SeaMeshService::class.java).apply {
                action = SeaMeshService.ACTION_STOP_SCAN
            }
            startService(intent)
        }

        if (isScannerActive && scanCallback != null) {
            try {
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                btManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan in MainActivity: ${e.message}")
            }
            scanCallback = null
            isScannerActive = false
        }
        SeaMeshStateHolder.updateScanning(false)
    }
}
