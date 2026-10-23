package com.example.data

import android.content.Context
import android.content.SharedPreferences

class SeaMeshPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("seamesh_settings", Context.MODE_PRIVATE)

    var isActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var savedDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    var savedDeviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    fun clearDevice() {
        prefs.edit().remove(KEY_DEVICE_ADDRESS).remove(KEY_DEVICE_NAME).apply()
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://seamesh.onrender.com"
        private const val KEY_ACTIVE = "key_active"
        private const val KEY_SERVER_URL = "key_server_url"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_DEVICE_ADDRESS = "key_device_address"
        private const val KEY_DEVICE_NAME = "key_device_name"
    }
}
