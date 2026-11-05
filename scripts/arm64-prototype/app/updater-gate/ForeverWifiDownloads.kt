package app.gamenative

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Per-request Wi-Fi eligibility and binding. Never binds the process or the game.
 * An active VPN uses the system default route instead of binding to its tunnel:
 * a tailnet-only VPN without a public default route then falls through to the
 * validated Wi-Fi underlay, while an exit-node VPN keeps its tunnel. Cellular,
 * including a VPN whose underlying transport is cellular, is never eligible.
 */
object ForeverWifiDownloads {
    class Unavailable : IOException("wifi_required")
    private var manager: ConnectivityManager? = null
    private val connections = WeakHashMap<HttpURLConnection, Network>()
    private val calls = WeakHashMap<Call, Network>()
    private data class BoundClient(val base: OkHttpClient, val network: Network, val client: OkHttpClient)
    private data class UnboundClient(val base: OkHttpClient, val client: OkHttpClient)
    private var boundClient: BoundClient? = null
    private var unboundClient: UnboundClient? = null
    private var boundNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Re-evaluate the selected route. A new VPN without public internet
            // must not displace the validated Wi-Fi underlay.
            val selected = runCatching { network() }.getOrNull()
            val previous = synchronized(this@ForeverWifiDownloads) {
                (connections.values + calls.values + listOfNotNull(boundNetwork, boundClient?.network))
                    .filter { it != selected }.distinct()
            }
            previous.forEach(::interrupt)
        }
        override fun onLost(network: Network) = interrupt(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!eligible(caps) || !stillSelected(network)) interrupt(network)
        }
    }

    @Synchronized fun initialize(context: Context) {
        val service = (context.applicationContext ?: context).getSystemService(ConnectivityManager::class.java)
        if (service === manager) return
        manager?.let { runCatching { it.unregisterNetworkCallback(callback) } }
        service.registerDefaultNetworkCallback(callback)
        manager = service
        boundNetwork = null
        unboundClient = null
    }

    internal fun eligible(caps: NetworkCapabilities?): Boolean = caps != null &&
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    @Synchronized private fun network(): Network {
        val service = manager ?: throw Unavailable()
        val active = service.activeNetwork
        val activeRoute = active?.let { hasDefaultRoute(service, it) }
        val selected = selectNetwork(
            active,
            active?.let(service::getNetworkCapabilities),
            service.allNetworks.mapNotNull { candidate ->
                service.getNetworkCapabilities(candidate)?.let { candidate to it }
            },
            { candidate -> hasDefaultRoute(service, candidate) },
        ) ?: throw Unavailable()
        android.util.Log.d(
            "ForeverWifiDownloads",
            "active=$active defaultRoute=$activeRoute selected=$selected",
        )
        // Android's URLConnection pool belongs to the Network *instance*, not
        // its netId. Retain one representative while freshly checking eligibility.
        return boundNetwork?.takeIf { it == selected } ?: selected.also { boundNetwork = it }
    }

    /**
     * Prefer the active network only when it can carry public traffic. A VPN
     * without a default route (for example a tailnet-only VPN) cannot reach the
     * update host; use a validated non-VPN Wi-Fi network instead.
     */
    internal fun selectNetwork(
        active: Network?,
        activeCaps: NetworkCapabilities?,
        candidates: List<Pair<Network, NetworkCapabilities>>,
        hasDefaultRoute: (Network) -> Boolean,
    ): Network? {
        if (active != null && activeCaps != null && eligible(activeCaps)) {
            val vpn = activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (!vpn || hasDefaultRoute(active)) return active
        }
        return candidates.firstOrNull { (_, caps) ->
            eligible(caps) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }?.first
    }

    private fun hasDefaultRoute(service: ConnectivityManager, network: Network): Boolean = runCatching {
        service.getLinkProperties(network)?.routes?.any { it.isDefaultRoute } == true
    }.getOrDefault(false)

    private fun stillSelected(network: Network): Boolean =
        runCatching { network() == network }.getOrDefault(false)

    private fun vpnActive(): Boolean {
        val service = manager ?: return false
        val caps = service.activeNetwork?.let(service::getNetworkCapabilities) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun available(): Boolean = try { network(); true } catch (_: Unavailable) { false }
    fun requireAvailable() { network() }

    fun open(url: URL): HttpURLConnection {
        val network = network()
        // A VPN can be active while its tunnel has no public default route
        // (tailnet-only). Android rejects binding the underlying Wi-Fi with
        // EPERM, so use the system default route for an active VPN.
        val connection = if (vpnActive()) {
            url.openConnection() as HttpURLConnection
        } else {
            network.openConnection(url) as HttpURLConnection
        }
        synchronized(this) { connections[connection] = network }
        if (!stillSelected(network)) {
            connection.disconnect()
            throw Unavailable()
        }
        return connection
    }

    fun newCall(base: OkHttpClient, request: Request): Call {
        val network = network()
        // Reuse sockets within Wi-Fi, never the base client's possibly cellular
        // pool. An active VPN uses the system default route because binding its
        // underlay is refused with EPERM.
        val client = if (vpnActive()) {
            synchronized(this) {
                unboundClient?.takeIf { it.base === base }?.client ?:
                    base.newBuilder().connectionPool(okhttp3.ConnectionPool()).build()
                        .also { unboundClient = UnboundClient(base, it) }
            }
        } else {
            synchronized(this) {
                boundClient?.takeIf { it.base === base && it.network == network }?.client ?:
                    base.newBuilder().socketFactory(network.socketFactory)
                        .dns { host -> network.getAllByName(host).toList() }
                        .connectionPool(okhttp3.ConnectionPool()).build()
                        .also { boundClient = BoundClient(base, network, it) }
            }
        }
        val call = client.newCall(request)
        synchronized(this) { calls[call] = network }
        if (!stillSelected(network)) {
            call.cancel()
            throw Unavailable()
        }
        return call
    }

    private fun interrupt(network: Network) {
        val affected = synchronized(this) {
            if (boundNetwork == network) boundNetwork = null
            unboundClient = null
            val pool = boundClient?.takeIf { it.network == network }?.client?.connectionPool
            if (pool != null) boundClient = null
            Triple(connections.filterValues { it == network }.keys.toList(),
                calls.filterValues { it == network }.keys.toList(), pool)
        }
        affected.first.forEach { runCatching { it.disconnect() } }
        affected.second.forEach { it.cancel() }
        affected.third?.evictAll()
    }
}
