package app.gamenative

import android.app.Application
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.net.NetworkInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import okhttp3.OkHttpClient
import okhttp3.Request

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ForeverWifiDownloadsTest {
    @Test fun wifiLossCancelsBoundCallsAndDoesNotFallBackToAvailableCellular() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val connectivity = shadowOf(manager)
        connectivity.clearAllNetworks()
        val cellular = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
        val cellCaps = NetworkCapabilities()
        shadowOf(cellCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val cellInfo = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE, 0, true, true)
        connectivity.setDefaultNetworkActive(true)
        connectivity.setActiveNetworkInfo(cellInfo)
        connectivity.setNetworkCapabilities(cellular, cellCaps)
        ForeverWifiDownloads.initialize(app)
        assertFalse(ForeverWifiDownloads.available())

        val wifi = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val wifiCaps = NetworkCapabilities()
        shadowOf(wifiCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val wifiInfo = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true)
        connectivity.setActiveNetworkInfo(wifiInfo)
        connectivity.setNetworkCapabilities(wifi, wifiCaps)
        assertTrue(ForeverWifiDownloads.available())
        val selectedNetwork = ForeverWifiDownloads::class.java.getDeclaredMethod("network").apply { isAccessible = true }
        val firstNetwork = selectedNetwork.invoke(ForeverWifiDownloads)
        connectivity.setActiveNetworkInfo(wifiInfo) // New wrapper, same netId.
        org.junit.Assert.assertSame(firstNetwork, selectedNetwork.invoke(ForeverWifiDownloads))
        val call = ForeverWifiDownloads.newCall(OkHttpClient(), Request.Builder().url("https://example.invalid/").build())
        assertFalse(call.isCanceled())

        // A VPN over Wi-Fi is eligible and routes through the tunnel; the raw
        // Wi-Fi connection is interrupted so it cannot bypass the VPN.
        val vpn = ShadowNetwork.newInstance(ConnectivityManager.TYPE_VPN)
        val vpnCaps = NetworkCapabilities()
        shadowOf(vpnCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        connectivity.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_VPN, 0, true, true))
        connectivity.setNetworkCapabilities(vpn, vpnCaps)
        connectivity.networkCallbacks.forEach { it.onAvailable(vpn) }
        assertTrue(call.isCanceled())
        assertTrue(ForeverWifiDownloads.available())
        ForeverWifiDownloads.requireAvailable()
        val vpnCall = ForeverWifiDownloads.newCall(OkHttpClient(), Request.Builder().url("https://example.invalid/").build())
        assertFalse(vpnCall.isCanceled())

        // A VPN whose underlying transport becomes cellular is not eligible.
        shadowOf(vpnCaps).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(vpnCaps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        connectivity.networkCallbacks.forEach { it.onCapabilitiesChanged(vpn, vpnCaps) }
        assertTrue(vpnCall.isCanceled())
        assertFalse(ForeverWifiDownloads.available())
        assertThrows(ForeverWifiDownloads.Unavailable::class.java) { ForeverWifiDownloads.requireAvailable() }
        connectivity.setActiveNetworkInfo(wifiInfo)
        val resumed = ForeverWifiDownloads.newCall(OkHttpClient(), Request.Builder().url("https://example.invalid/").build())
        connectivity.removeNetwork(wifi)
        connectivity.setActiveNetworkInfo(cellInfo)
        connectivity.networkCallbacks.forEach { it.onLost(wifi) }
        assertTrue(resumed.isCanceled())
        assertFalse(ForeverWifiDownloads.available())
        assertThrows(ForeverWifiDownloads.Unavailable::class.java) {
            ForeverWifiDownloads.newCall(OkHttpClient(), Request.Builder().url("https://example.invalid/").build())
        }
    }

    @Test fun unmeteredCellularAndUnvalidatedWifiAreNotEligible() {
        val caps = NetworkCapabilities()
        val shadow = shadowOf(caps)
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        assertFalse(ForeverWifiDownloads.eligible(caps))
        shadow.removeTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(ForeverWifiDownloads.eligible(caps))
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        assertTrue(ForeverWifiDownloads.eligible(caps))
        shadow.removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        assertFalse(ForeverWifiDownloads.eligible(caps))
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertFalse(ForeverWifiDownloads.eligible(caps))
        assertFalse(ForeverWifiDownloads.eligible(null))
    }

    @Test fun noUsableWifiBlocksBeforeAnyHttpRequest() {
        ForeverWifiDownloads.initialize(RuntimeEnvironment.getApplication())
        assertFalse(ForeverWifiDownloads.available())
        try {
            ForeverWifiDownloads.open(java.net.URL("https://example.invalid/"))
            fail("network work must not start without usable Wi-Fi")
        } catch (expected: ForeverWifiDownloads.Unavailable) {
            assertTrue(ForeverFunctionalUiText.userMessage(expected).contains("Wi-Fi"))
        }
    }
}
