package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SeaMeshPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SeaMesh", appName)
    }

    @Test
    fun `preferences store and retrieve correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SeaMeshPreferences(context)

        prefs.isActive = false
        assertFalse(prefs.isActive)

        prefs.isActive = true
        assertTrue(prefs.isActive)

        prefs.serverUrl = "https://custom-server.test"
        assertEquals("https://custom-server.test", prefs.serverUrl)

        prefs.apiKey = "secret_marine_key"
        assertEquals("secret_marine_key", prefs.apiKey)

        prefs.savedDeviceAddress = "00:11:22:33:44:55"
        prefs.savedDeviceName = "SeaMesh-Boat01"
        assertEquals("00:11:22:33:44:55", prefs.savedDeviceAddress)
        assertEquals("SeaMesh-Boat01", prefs.savedDeviceName)
    }
}
