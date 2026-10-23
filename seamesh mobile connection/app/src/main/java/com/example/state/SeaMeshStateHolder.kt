package com.example.state

import android.location.Location
import com.example.model.BleConnectionStatus
import com.example.model.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SeaMeshUiState(
    val isActive: Boolean = false,
    val savedDeviceName: String? = null,
    val savedDeviceAddress: String? = null,
    val connectionStatus: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val gpsStatus: String = "Waiting for fix...",
    val latestLat: Double? = null,
    val latestLon: Double? = null,
    val latestAccuracy: Float? = null,
    val latestFixTimeSeconds: Long? = null,
    val fixAgeSeconds: Long? = null,
    val isOnline: Boolean = false,
    val lastUploadText: String = "No uploads yet",
    val queueSize: Int = 0,
    val serverUrl: String = "https://seamesh-api.onrender.com",
    val apiKey: String = "",
    val isBluetoothEnabled: Boolean = true,
    val isGpsEnabled: Boolean = true,
    val isScanning: Boolean = false,
    val discoveredDevices: List<DiscoveredDevice> = emptyList()
)

object SeaMeshStateHolder {
    private val _uiState = MutableStateFlow(SeaMeshUiState())
    val uiState: StateFlow<SeaMeshUiState> = _uiState.asStateFlow()

    fun updateActive(active: Boolean) {
        _uiState.update { it.copy(isActive = active) }
    }

    fun updateSavedDevice(name: String?, address: String?) {
        _uiState.update { it.copy(savedDeviceName = name, savedDeviceAddress = address) }
    }

    fun updateConnectionStatus(status: BleConnectionStatus) {
        _uiState.update { it.copy(connectionStatus = status) }
    }

    fun updateGpsLocation(location: Location?) {
        if (location == null) {
            _uiState.update {
                it.copy(
                    gpsStatus = "Waiting for fix...",
                    latestLat = null,
                    latestLon = null,
                    latestAccuracy = null,
                    latestFixTimeSeconds = null,
                    fixAgeSeconds = null
                )
            }
            return
        }

        val fixTimeSec = location.time / 1000L
        val ageSec = (System.currentTimeMillis() / 1000L) - fixTimeSec
        val safeAge = if (ageSec < 0) 0 else ageSec

        val statusText = String.format(
            java.util.Locale.US,
            "%.6f, %.6f (±%.1f m), fix age %d s",
            location.latitude,
            location.longitude,
            location.accuracy,
            safeAge
        )

        _uiState.update {
            it.copy(
                gpsStatus = statusText,
                latestLat = location.latitude,
                latestLon = location.longitude,
                latestAccuracy = location.accuracy,
                latestFixTimeSeconds = fixTimeSec,
                fixAgeSeconds = safeAge
            )
        }
    }

    fun updateFixAge() {
        val fixTimeSec = _uiState.value.latestFixTimeSeconds ?: return
        val lat = _uiState.value.latestLat ?: return
        val lon = _uiState.value.latestLon ?: return
        val acc = _uiState.value.latestAccuracy ?: 0f

        val ageSec = (System.currentTimeMillis() / 1000L) - fixTimeSec
        val safeAge = if (ageSec < 0) 0 else ageSec

        val statusText = String.format(
            java.util.Locale.US,
            "%.6f, %.6f (±%.1f m), fix age %d s",
            lat,
            lon,
            acc,
            safeAge
        )

        _uiState.update {
            it.copy(
                gpsStatus = statusText,
                fixAgeSeconds = safeAge
            )
        }
    }

    fun updateInternetStatus(isOnline: Boolean) {
        _uiState.update { it.copy(isOnline = isOnline) }
    }

    fun updateLastUpload(text: String) {
        _uiState.update { it.copy(lastUploadText = text) }
    }

    fun updateQueueSize(size: Int) {
        _uiState.update { it.copy(queueSize = size) }
    }

    fun updateSettings(serverUrl: String, apiKey: String) {
        _uiState.update { it.copy(serverUrl = serverUrl, apiKey = apiKey) }
    }

    fun updateHardwareStatus(bluetoothEnabled: Boolean, gpsEnabled: Boolean) {
        _uiState.update {
            it.copy(
                isBluetoothEnabled = bluetoothEnabled,
                isGpsEnabled = gpsEnabled
            )
        }
    }

    fun updateScanning(isScanning: Boolean) {
        _uiState.update { it.copy(isScanning = isScanning) }
    }

    fun updateDiscoveredDevices(devices: List<DiscoveredDevice>) {
        _uiState.update { it.copy(discoveredDevices = devices) }
    }
}
