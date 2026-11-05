package app.gamenative

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ForeverWifiDownloadsTest {
    @Test
    fun wifiLossCancelsBoundCallsAndTailnetVpnUsesWifiUnderlay() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val connectivity = shadowOf(manager)
        connectivity.clearAllNetworks()

        val cellular = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
        val cellCaps = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)
        val cellInfo = networkInfo(ConnectivityManager.TYPE_MOBILE)
        connectivity.setDefaultNetworkActive(true)
        connectivity.setActiveNetworkInfo(cellInfo)
        connectivity.setNetworkCapabilities(cellular, cellCaps)
        ForeverWifiDownloads.initialize(app)
        assertFalse(ForeverWifiDownloads.available())
        assertThrows(ForeverWifiDownloads.Unavailable::class.java) { ForeverWifiDownloads.requireAvailable() }

        val wifi = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val wifiCaps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        val wifiInfo = networkInfo(ConnectivityManager.TYPE_WIFI)
        connectivity.setActiveNetworkInfo(wifiInfo)
        connectivity.setNetworkCapabilities(wifi, wifiCaps)
        assertTrue(ForeverWifiDownloads.available())
        val call = ForeverWifiDownloads.newCall(OkHttpClient(), Request.Builder().url("https://example.invalid/").build())
        assertFalse(call.isCanceled())

        // A tailnet-only VPN has no public default route; keep the validated
        // Wi-Fi underlay instead of binding requests to the tunnel.
        val vpn = ShadowNetwork.newInstance(ConnectivityManager.TYPE_VPN)
        val vpnCaps = capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)
        connectivity.setActiveNetworkInfo(networkInfo(ConnectivityManager.TYPE_VPN))
        connectivity.setNetworkCapabilities(vpn, vpnCaps)
        connectivity.networkCallbacks.forEach { it.onAvailable(vpn) }
        assertFalse(call.isCanceled())
        assertTrue(ForeverWifiDownloads.available())
        val fallbackCall = ForeverWifiDownloads.newCall(OkHttpClient(), Request.Builder().url("https://example.invalid/").build())
        assertFalse(fallbackCall.isCanceled())

        // Losing the Wi-Fi underlay leaves only a cellular path; never fall back.
        connectivity.removeNetwork(wifi)
        connectivity.networkCallbacks.forEach { it.onLost(wifi) }
        assertTrue(fallbackCall.isCanceled())
        assertFalse(ForeverWifiDownloads.available())
        assertThrows(ForeverWifiDownloads.Unavailable::class.java) {
            ForeverWifiDownloads.newCall(OkHttpClient(), Request.Builder().url("https://example.invalid/").build())
        }
    }

    @Test
    fun selectNetworkUsesVpnWithRouteAndFallsBackForTailnetOnlyVpn() {
        val wifi = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val wifiCaps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        val vpn = ShadowNetwork.newInstance(ConnectivityManager.TYPE_VPN)
        val vpnCaps = capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)
        val cellular = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
        val cellCaps = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)
        val candidates = listOf(vpn to vpnCaps, wifi to wifiCaps, cellular to cellCaps)

        assertEquals(vpn, ForeverWifiDownloads.selectNetwork(vpn, vpnCaps, candidates) { true })
        assertEquals(wifi, ForeverWifiDownloads.selectNetwork(vpn, vpnCaps, candidates) { false })
        assertEquals(wifi, ForeverWifiDownloads.selectNetwork(wifi, wifiCaps, candidates) { false })
        assertNull(ForeverWifiDownloads.selectNetwork(cellular, cellCaps, listOf(cellular to cellCaps)) { true })
        assertNull(ForeverWifiDownloads.selectNetwork(null, null, listOf(cellular to cellCaps)) { true })
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

    private fun capabilities(vararg transports: Int): NetworkCapabilities =
        NetworkCapabilities().also { caps ->
            shadowOf(caps).apply {
                transports.forEach(::addTransportType)
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }

    private fun networkInfo(type: Int): NetworkInfo =
        ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true)
}
