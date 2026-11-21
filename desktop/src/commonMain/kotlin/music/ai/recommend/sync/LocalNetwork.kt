package music.ai.recommend.sync

import java.net.DatagramSocket
import java.net.Socket

/**
 * Puts a socket on the network the other device is actually on.
 *
 * Needed because of what a VPN is: it takes the whole routing table, including the addresses of
 * the machine in the next room. A phone with one running dials 10.220.68.119, the tunnel accepts
 * the connection locally — so `connect` succeeds and nothing is wrong as far as the caller can see
 * — and then has nowhere to deliver it, because the other end of the tunnel is in another country
 * and knows nothing about this living room. Measured exactly that way: every dial from the phone
 * came back as "would not open", the desktop never saw a connection at all, and a probe from the
 * desktop to its own port answered instantly.
 *
 * It is not an exotic case. In Russia a VPN is ordinary, and an offline music player is wanted
 * most by the people most likely to be running one, so "sync works unless you have a VPN" is not
 * a limit this feature can have.
 *
 * The way out is per-socket rather than global: the platform says which network the local devices
 * are on and the socket is put on it, leaving everything else in the app on the tunnel where the
 * owner wants it. On Android that is `Network.bindSocket`, which has to happen **before** the
 * socket is connected — the one ordering mistake that would silently do nothing — and which the
 * system refuses for a VPN set to block connections without it. Then there is nothing to be done,
 * and the honest answer is the one the owner already gets: nobody could be reached.
 *
 * Null wherever a platform has nothing to say, which is most of them: a desktop's VPN leaves the
 * routes of its own subnet alone, so a socket to the phone next door goes out of the right
 * interface without anybody's help.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface LocalNetwork {

    /**
     * Sends this socket's traffic over the local network.
     *
     * @return false when it could not be done, which is not an error: the caller goes on and the
     *   socket takes whatever route the system would have given it anyway.
     */
    fun route(socket: Socket): Boolean

    /** The same for a datagram socket, which is what the beacon announces itself with. */
    fun route(socket: DatagramSocket): Boolean
}
