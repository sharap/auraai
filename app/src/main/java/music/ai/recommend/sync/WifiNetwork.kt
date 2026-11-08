package music.ai.recommend.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.DatagramSocket
import java.net.Socket

/**
 * [LocalNetwork] on Android: the wifi this phone is on, whatever the routing table says.
 *
 * `bindSocket` is the whole of it. The system keeps a route per network, and a socket bound to one
 * uses that route rather than the default — which with a VPN up is the tunnel. So this is not a way
 * round the owner's VPN but a way to the device in the same room, and nothing else in the app is
 * affected.
 *
 * The network is looked up per socket and never held. A phone moves between wifi, a hotspot and
 * nothing at all several times an hour, and a handle kept from the last pass would be a handle to a
 * network that has gone.
 */
class WifiNetwork(private val context: Context) : LocalNetwork {

    override fun route(socket: Socket): Boolean {
        val network = local() ?: return false
        if (onLocal(network) { it.bindSocket(socket) }) return true
        // The second way, when the system refuses the first. A VPN that did not ask to be
        // bypassable makes `bindSocket` fail with EPERM — measured on this phone, against a
        // perfectly ordinary VPN app — and that is the system keeping the owner's promise rather
        // than a fault. But the address of the local network is still ours to ask for, and a
        // socket speaking *from* that address may be routed straight at a destination on the same
        // subnet instead of into the tunnel. It costs one system call to try, and it is the
        // difference between a phone that can dial and one that can only be dialled.
        val address = addressOf(network) ?: return false
        return try {
            socket.bind(java.net.InetSocketAddress(address, 0))
            true
        } catch (e: Exception) {
            Log.i(TAG, "Could not speak from the local address either: ${e.message}")
            false
        }
    }

    /**
     * The same for a datagram socket, which is what the beacon announces itself with.
     *
     * No second way here: a [DatagramSocket] is bound the moment it is made, so there is no address
     * left to choose, and the beacon needs none — a broadcast to the local subnet reaches it even
     * with a tunnel up. Measured: five announcements from this phone arrived at the desktop in
     * forty-five seconds while every outgoing conversation from it was being swallowed.
     */
    override fun route(socket: DatagramSocket): Boolean {
        val network = local() ?: return false
        return onLocal(network) { it.bindSocket(socket) }
    }

    private fun addressOf(network: Network): java.net.InetAddress? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
    }

    private fun onLocal(network: Network, bind: (Network) -> Unit): Boolean {
        return try {
            bind(network)
            true
        } catch (e: Exception) {
            // A VPN set to block connections without it, a socket already connected, a network
            // that went away between the lookup and here. Nothing to do about any of them, and the
            // caller carries on with whatever route the system gives it.
            Log.i(TAG, "Could not put the socket on the local network: ${e.message}")
            false
        }
    }

    /**
     * The wifi or ethernet network, and never the VPN.
     *
     * Not `activeNetwork`, which is exactly the wrong answer here: with a tunnel up that *is* the
     * VPN. The list has to be walked instead. `allNetworks` is deprecated in favour of a callback
     * that reports changes over time, which is a different question from "which one is the local
     * network right now" and would mean holding state that goes stale.
     */
    @Suppress("DEPRECATION")
    private fun local(): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.firstOrNull { network ->
            val about = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            !about.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (about.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    about.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private companion object {
        const val TAG = "WifiNetwork"
    }
}
