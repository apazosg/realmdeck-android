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

/** Per-request Wi-Fi binding. Never binds the process or the running game. */
object ForeverWifiDownloads {
    class Unavailable(val vpnActive: Boolean = false) : IOException("wifi_required")
    private var manager: ConnectivityManager? = null
    private val connections = WeakHashMap<HttpURLConnection, Network>()
    private val calls = WeakHashMap<Call, Network>()
    private data class BoundClient(val base: OkHttpClient, val network: Network, val client: OkHttpClient)
    private var boundClient: BoundClient? = null
    private var boundNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Do not keep raw Wi-Fi sockets outside a newly selected VPN/default
            // route. Follow Android's default network rather than bypassing it.
            val previous = synchronized(this@ForeverWifiDownloads) {
                (connections.values + calls.values + listOfNotNull(boundNetwork, boundClient?.network))
                    .filter { it != network }.distinct()
            }
            previous.forEach(::interrupt)
        }
        override fun onLost(network: Network) = interrupt(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!eligible(caps)) interrupt(network)
        }
    }

    @Synchronized fun initialize(context: Context) {
        val service = (context.applicationContext ?: context).getSystemService(ConnectivityManager::class.java)
        if (service === manager) return
        manager?.let { runCatching { it.unregisterNetworkCallback(callback) } }
        service.registerDefaultNetworkCallback(callback)
        manager = service
        boundNetwork = null
    }

    internal fun eligible(caps: NetworkCapabilities?): Boolean = caps != null &&
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    @Synchronized private fun network(): Network {
        val service = manager ?: throw Unavailable()
        val network = service.activeNetwork
        val caps = network?.let(service::getNetworkCapabilities)
        if (network == null || !eligible(caps)) throw Unavailable(caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        // Android's URLConnection pool belongs to the Network *instance*, not
        // its netId. Retain one representative while freshly checking eligibility.
        return boundNetwork?.takeIf { it == network } ?: network.also { boundNetwork = it }
    }

    fun available(): Boolean = try { network(); true } catch (_: Unavailable) { false }
    fun requireAvailable() { network() }

    fun open(url: URL): HttpURLConnection {
        val network = network()
        val connection = network.openConnection(url) as HttpURLConnection
        synchronized(this) { connections[connection] = network }
        if (manager?.activeNetwork != network || !eligible(manager?.getNetworkCapabilities(network))) {
            connection.disconnect()
            throw Unavailable()
        }
        return connection
    }

    fun newCall(base: OkHttpClient, request: Request): Call {
        val network = network()
        // Reuse sockets within Wi-Fi, never the base client's possibly cellular
        // pool. DNS and sockets use the same captured network.
        val client = synchronized(this) {
            boundClient?.takeIf { it.base === base && it.network == network }?.client ?:
                base.newBuilder().socketFactory(network.socketFactory)
                    .dns { host -> network.getAllByName(host).toList() }
                    .connectionPool(okhttp3.ConnectionPool()).build()
                    .also { boundClient = BoundClient(base, network, it) }
        }
        val call = client.newCall(request)
        synchronized(this) { calls[call] = network }
        if (manager?.activeNetwork != network || !eligible(manager?.getNetworkCapabilities(network))) {
            call.cancel()
            throw Unavailable()
        }
        return call
    }

    private fun interrupt(network: Network) {
        val affected = synchronized(this) {
            if (boundNetwork == network) boundNetwork = null
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
