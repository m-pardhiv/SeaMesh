package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ble.BleManager
import com.example.data.PacketQueueManager
import com.example.data.SeaMeshPreferences
import com.example.model.BleConnectionStatus
import com.example.model.DiscoveredDevice
import com.example.model.SeaMeshPacket
import com.example.state.SeaMeshStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SeaMeshService : Service() {

    companion object {
        private const val TAG = "SeaMeshService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "seamesh_service_channel"

        const val ACTION_START = "com.example.seamesh.ACTION_START"
        const val ACTION_STOP = "com.example.seamesh.ACTION_STOP"
        const val ACTION_CONNECT_DEVICE = "com.example.seamesh.ACTION_CONNECT_DEVICE"
        const val ACTION_START_SCAN = "com.example.seamesh.ACTION_START_SCAN"
        const val ACTION_STOP_SCAN = "com.example.seamesh.ACTION_STOP_SCAN"

        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: SeaMeshPreferences
    private val packetQueue = PacketQueueManager(50)

    private var bleManager: BleManager? = null
    private var locationManager: LocationManager? = null
    private var connectivityManager: ConnectivityManager? = null

    private var latestLocation: Location? = null
    private var isOnline: Boolean = false
    private var gpsTickerJob: Job? = null
    private var queueProcessorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SeaMeshService onCreate")
        isRunning = true
        prefs = SeaMeshPreferences(this)
        createNotificationChannel()

        initBle()
        initNetworkMonitoring()
        initLocationMonitoring()
        startPeriodicJobs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_STOP -> {
                stopSeaMesh()
                return START_NOT_STICKY
            }
            ACTION_CONNECT_DEVICE -> {
                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val name = intent?.getStringExtra(EXTRA_DEVICE_NAME)
                if (!address.isNullOrBlank()) {
                    prefs.savedDeviceAddress = address
                    prefs.savedDeviceName = name ?: address
                    SeaMeshStateHolder.updateSavedDevice(prefs.savedDeviceName, address)
                    bleManager?.connect(address)
                }
            }
            ACTION_START_SCAN -> {
                SeaMeshStateHolder.updateScanning(true)
                bleManager?.startScan()
            }
            ACTION_STOP_SCAN -> {
                bleManager?.stopScan()
                SeaMeshStateHolder.updateScanning(false)
            }
            ACTION_START -> {
                startForegroundNotification()
                // Connect to saved MAC if available
                val savedMac = prefs.savedDeviceAddress
                if (!savedMac.isNullOrBlank()) {
                    bleManager?.connect(savedMac)
                } else {
                    SeaMeshStateHolder.updateConnectionStatus(BleConnectionStatus.NOT_FOUND)
                }
            }
        }

        startForegroundNotification()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildNotification("SeaMesh active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No dataSync type: Android 15 stops dataSync services after 6 hours
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SeaMesh Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for SeaMesh Bluetooth, GPS, and LoRa gateway"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val stopIntent = Intent(this, SeaMeshService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SeaMesh active")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationText(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun initBle() {
        bleManager = BleManager(
            context = this,
            onConnectionStatusChanged = { status ->
                SeaMeshStateHolder.updateConnectionStatus(status)
                val statusString = when (status) {
                    BleConnectionStatus.CONNECTED -> "Connected"
                    BleConnectionStatus.CONNECTING -> "Connecting..."
                    BleConnectionStatus.NOT_FOUND -> "Not found"
                    BleConnectionStatus.DISCONNECTED -> "Disconnected"
                }
                updateNotificationText("SeaMesh active • $statusString")
            },
            onPacketReceived = { packet ->
                handleIncomingPacket(packet)
            },
            onDiscoveredDevicesChanged = { devices ->
                SeaMeshStateHolder.updateDiscoveredDevices(devices)
            }
        ).apply {
            onConnectionReady = {
                // "Write "NET,1"/"NET,0" right after connecting and whenever it changes."
                writeInternetStatus(isOnline)

                // "write the latest fix to GPS every 5 s and right after connecting."
                latestLocation?.let { loc ->
                    val fixTimeSec = loc.time / 1000L
                    writeGps(loc.latitude, loc.longitude, loc.accuracy, fixTimeSec)
                }
            }
        }
    }

    private fun initNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                // "treat the phone as online only when NET_CAPABILITY_VALIDATED is present."
                val online = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                setOnlineState(online)
            }

            override fun onLost(network: Network) {
                setOnlineState(false)
            }
        }

        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            // Initial check
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            val initialOnline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            setOnlineState(initialOnline)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback: ${e.message}")
        }
    }

    private fun setOnlineState(online: Boolean) {
        if (isOnline != online) {
            isOnline = online
            Log.d(TAG, "Network state changed: isOnline = $isOnline")
            SeaMeshStateHolder.updateInternetStatus(online)
            // "Write "NET,1"/"NET,0" right after connecting and whenever it changes."
            bleManager?.writeInternetStatus(online)

            if (online) {
                triggerQueueUpload()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initLocationMonitoring() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        SeaMeshStateHolder.updateHardwareStatus(
            bluetoothEnabled = bleManager?.isBluetoothEnabled == true,
            gpsEnabled = isGpsEnabled
        )

        try {
            // "Use LocationManager.GPS_PROVIDER only."
            // "GPS updates every 5 s"
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,
                0f,
                locationListener
            )
            // Get last known GPS provider location if available
            val lastKnown = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnown != null) {
                onNewLocation(lastKnown)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting GPS updates: ${e.message}")
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNewLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                SeaMeshStateHolder.updateHardwareStatus(
                    bluetoothEnabled = bleManager?.isBluetoothEnabled == true,
                    gpsEnabled = true
                )
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                SeaMeshStateHolder.updateHardwareStatus(
                    bluetoothEnabled = bleManager?.isBluetoothEnabled == true,
                    gpsEnabled = false
                )
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun onNewLocation(location: Location) {
        latestLocation = location
        SeaMeshStateHolder.updateGpsLocation(location)
    }

    private fun startPeriodicJobs() {
        // GPS & Age ticker every 1 second:
        // Updates fix age every second, and every 5 seconds writes GPS to BLE
        gpsTickerJob?.cancel()
        gpsTickerJob = serviceScope.launch {
            var counter = 0
            while (isActive) {
                delay(1000L)
                SeaMeshStateHolder.updateFixAge()

                // Hardware checks
                val btOn = bleManager?.isBluetoothEnabled == true
                val gpsOn = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
                SeaMeshStateHolder.updateHardwareStatus(btOn, gpsOn)

                counter++
                if (counter >= 5) {
                    counter = 0
                    // "GPS updates every 5 s; write the latest fix to GPS every 5 s and right after connecting."
                    latestLocation?.let { loc ->
                        val fixTimeSec = loc.time / 1000L
                        bleManager?.writeGps(loc.latitude, loc.longitude, loc.accuracy, fixTimeSec)
                    }
                }
            }
        }

        // Queue upload retry job: "retry every 5 s while online"
        queueProcessorJob?.cancel()
        queueProcessorJob = serviceScope.launch {
            while (isActive) {
                delay(5000L)
                if (isOnline && packetQueue.size() > 0) {
                    processPacketQueue()
                }
            }
        }
    }

    private fun handleIncomingPacket(packet: SeaMeshPacket) {
        serviceScope.launch {
            Log.d(TAG, "Handling packet: ${packet.boat} msgId=${packet.msgId} type=${packet.type}")

            // If already successfully uploaded, immediately ACK to ESP32
            if (packetQueue.isAlreadyUploaded(packet)) {
                Log.d(TAG, "Packet ${packet.key} was already uploaded; sending ACK again to ESP32")
                bleManager?.writeAck(packet.boat, packet.msgId)
                return@launch
            }

            if (isOnline) {
                val success = uploadPacketToServer(packet)
                if (success) {
                    onUploadSuccess(packet)
                } else {
                    onUploadFailure(packet)
                }
            } else {
                Log.d(TAG, "Offline: Enqueueing packet ${packet.key}")
                packetQueue.enqueue(packet)
                SeaMeshStateHolder.updateQueueSize(packetQueue.size())
            }
        }
    }

    private fun triggerQueueUpload() {
        serviceScope.launch {
            processPacketQueue()
        }
    }

    private suspend fun processPacketQueue() {
        if (!isOnline) return
        val batch = packetQueue.getNextBatch()
        for (packet in batch) {
            if (!isOnline) break
            val success = uploadPacketToServer(packet)
            if (success) {
                onUploadSuccess(packet)
            } else {
                // Keep in queue and stop this iteration
                onUploadFailure(packet)
                break
            }
        }
    }

    private fun onUploadSuccess(packet: SeaMeshPacket) {
        packetQueue.markUploaded(packet)
        SeaMeshStateHolder.updateQueueSize(packetQueue.size())

        // "On HTTP 2xx, write "ACK,<boat>,<msgId>" to CONTROL."
        bleManager?.writeAck(packet.boat, packet.msgId)

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val packetTypeStr = if (packet.isSos) "SOS" else "Position"
        val statusMsg = "$packetTypeStr from ${packet.boat} uploaded $timeStr"
        Log.i(TAG, statusMsg)
        SeaMeshStateHolder.updateLastUpload(statusMsg)
    }

    private fun onUploadFailure(packet: SeaMeshPacket) {
        packetQueue.enqueue(packet)
        SeaMeshStateHolder.updateQueueSize(packetQueue.size())
    }

    /**
     * HTTP upload via java.net.HttpURLConnection (no extra networking libraries):
     * POST to <Server URL>/api/alerts
     * Headers: Content-Type: application/json, X-API-Key: <API key>
     * 10 s timeouts
     */
    private suspend fun uploadPacketToServer(packet: SeaMeshPacket): Boolean {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val baseUrl = prefs.serverUrl.trim().trimEnd('/')
                val fullUrl = "$baseUrl/api/alerts"
                Log.d(TAG, "Uploading packet ${packet.key} to $fullUrl")

                val url = URL(fullUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json")
                    val apiKey = prefs.apiKey.trim()
                    if (apiKey.isNotEmpty()) {
                        setRequestProperty("X-API-Key", apiKey)
                    }
                }

                val payload = packet.toJson()
                val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payloadBytes.size)

                val os: OutputStream = connection.outputStream
                os.write(payloadBytes)
                os.flush()
                os.close()

                val responseCode = connection.responseCode
                Log.d(TAG, "HTTP Response code: $responseCode")

                if (responseCode in 200..299) {
                    true
                } else {
                    val errorMsg = "Upload failed: HTTP $responseCode"
                    Log.w(TAG, errorMsg)
                    SeaMeshStateHolder.updateLastUpload(errorMsg)
                    false
                }
            } catch (e: Exception) {
                val errorMsg = "Upload failed: ${e.message ?: "Network error"}"
                Log.e(TAG, errorMsg)
                SeaMeshStateHolder.updateLastUpload(errorMsg)
                false
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun stopSeaMesh() {
        Log.d(TAG, "Stopping SeaMeshService...")
        prefs.isActive = false
        SeaMeshStateHolder.updateActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SeaMeshService onDestroy")
        isRunning = false

        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
        }

        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates: ${e.message}")
        }

        bleManager?.disconnect()
        bleManager = null

        gpsTickerJob?.cancel()
        queueProcessorJob?.cancel()
        serviceScope.cancel()

        SeaMeshStateHolder.updateConnectionStatus(BleConnectionStatus.DISCONNECTED)
    }
}
