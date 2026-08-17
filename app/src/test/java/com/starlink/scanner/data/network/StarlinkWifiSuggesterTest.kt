package com.starlink.scanner.data.network

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the two pure helpers behind the device-wide dish WiFi path. The framework calls themselves
 * (`addNetworkSuggestions`, `getNetworkCapabilities`) need a real device, but the decisions worth
 * getting right — which statuses count as success, and which SSIDs are safe to suggest — don't.
 */
class StarlinkWifiSuggesterTest {

    @Test
    fun `success and duplicate both count as registered`() {
        assertEquals(
            SuggestResult.Registered,
            StarlinkWifiSuggester.describeAdd(WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS),
        )
        // Already registered is the state the caller wanted, not an error.
        assertEquals(
            SuggestResult.Registered,
            StarlinkWifiSuggester.describeAdd(
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            ),
        )
    }

    @Test
    fun `a declined app is reported with recovery instructions`() {
        val result = StarlinkWifiSuggester.describeAdd(
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED
        )
        val reason = (result as SuggestResult.Failed).reason
        // The technician can only fix this in Android settings, so the message has to say so.
        assertEquals(true, reason.contains("Settings"))
    }

    @Test
    fun `an unknown status still reports its code rather than claiming success`() {
        // The API 30+/33+ error codes land here; they must not be mistaken for a registration.
        val result = StarlinkWifiSuggester.describeAdd(9999)
        assertEquals(SuggestResult.Failed("WiFi suggestion failed (code 9999)"), result)
    }

    @Test
    fun `ssid is unwrapped from the quotes WifiInfo adds`() {
        assertEquals("STARLINK", StarlinkWifiConnector.normalizeSsid("\"STARLINK\""))
        assertEquals("STARLINK-1234", StarlinkWifiConnector.normalizeSsid("\"STARLINK-1234\""))
        // Already-bare values pass through untouched.
        assertEquals("STARLINK", StarlinkWifiConnector.normalizeSsid("STARLINK"))
    }

    @Test
    fun `a redacted or empty ssid is rejected instead of being suggested`() {
        // What the platform returns when location permission would be needed to see the real name.
        assertNull(StarlinkWifiConnector.normalizeSsid("<unknown ssid>"))
        assertNull(StarlinkWifiConnector.normalizeSsid("\"<unknown ssid>\""))
        assertNull(StarlinkWifiConnector.normalizeSsid(""))
        assertNull(StarlinkWifiConnector.normalizeSsid("\"\""))
        assertNull(StarlinkWifiConnector.normalizeSsid("   "))
        assertNull(StarlinkWifiConnector.normalizeSsid(null))
    }
}
