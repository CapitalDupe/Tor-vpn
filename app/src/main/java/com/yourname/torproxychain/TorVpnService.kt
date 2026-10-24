package com.yourname.torproxychain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/**
 * VPN entry point.
 *
 *  1. Wait for Tor (Orbot) if the chain uses it.
 *  2. Start the local SOCKS5 chain relay on 127.0.0.1:1080 (ProxyChainSocksServer).
 *  3. Open the tun interface with VpnService.Builder (all device traffic is routed here).
 *  4. Hand the tun fd to hev-socks5-tunnel (JNI), which turns IP packets into SOCKS5 connections
 *     to the relay and answers DNS itself ("mapdns") so names are resolved by the far end of the
 *     chain (no DNS leaks).
 *
 * Sockets for hop-to-hop connections are protect()'ed, otherwise they'd be captured by the tun
 * interface and loop forever.
 */
class TorVpnService : VpnService() {

    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    companion object {
        private const val TAG = "TorVpnService"
        const val CHANNEL_ID = "tor_proxy_chain_vpn"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.yourname.torproxychain.STOP"

        private const val LOCAL_SOCKS_PORT = 1080
        private const val MTU = 1500
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val MAPPED_DNS = "198.18.0.2"
        private const val TOR_START_TIMEOUT_MS = 90_000L

        @Volatile var state: State = State.STOPPED
            private set
        @Volatile var lastError: String? = null
            private set
    }

    private val lock = Any()
    private var worker: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var chainServer: ProxyChainSocksServer? = null
    private var tunnelStarted = false
    @Volatile private var cancelled = false
    @Volatile private var underlyingNetwork: Network? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat("Starting…")

        synchronized(lock) {
            if (worker?.isAlive == true || state == State.RUNNING) return START_STICKY
            cancelled = false
            lastError = null
            state = State.STARTING
            worker = Thread({ runVpn() }, "tor-vpn-start").also { it.start() }
        }
        return START_STICKY
    }

    // ---- startup (background thread) ----

    private fun runVpn() {
        try {
            val chain = ProxyStore.normalize(ProxyStore.loadChain(this))
            val usesTor = chain.any { it.isTor }

            if (usesTor) {
                updateNotification("Waiting for Tor (Orbot)…")
                if (!OrbotManager.isOrbotInstalled(this)) return fail("Orbot is not installed.")
                if (!OrbotManager.ensureTorRunning(this, TOR_START_TIMEOUT_MS)) {
                    if (cancelled) return
                    return fail("Tor did not start. Open Orbot, press Start, then connect again.")
                }
            }
            if (cancelled) return

            // Remember the real network now; once the VPN is up this is the only way to resolve
            // names without going through the tunnel.
            underlyingNetwork = getSystemService(ConnectivityManager::class.java).activeNetwork

            val server = ProxyChainSocksServer(
                listenPort = LOCAL_SOCKS_PORT,
                getChain = { chain },
                protectSocket = { sock -> protect(sock) },
                resolve = ::resolveOutsideVpn,
                onLog = { Log.d(TAG, it) }
            )
            try {
                server.start()
            } catch (e: Exception) {
                return fail("Could not open local port $LOCAL_SOCKS_PORT: ${e.message}")
            }
            chainServer = server

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress(TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPPED_DNS)          // answered by hev's mapdns, never leaves the device
                // Capture IPv6 too, otherwise it would bypass the VPN entirely.
                .addAddress(TUN_IPV6, 128)
                .addRoute("::", 0)
            underlyingNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            if (usesTor) {
                // Orbot's own connections to the Tor network must not go through us (it would loop).
                try { builder.addDisallowedApplication(OrbotManager.ORBOT_PACKAGE) }
                catch (_: PackageManager.NameNotFoundException) {}
            }

            val pfd = builder.establish() ?: return fail("VPN permission was not granted.")
            vpnInterface = pfd
            if (cancelled) { stopVpn(); return }

            val config = File(filesDir, "tun2socks.yml")
            config.writeText(buildTunnelConfig())
            val started = try {
                TProxyService.TProxyStartService(config.absolutePath, pfd.fd)
            } catch (t: Throwable) {
                Log.e(TAG, "tun2socks unavailable", t)
                return fail("tun2socks library missing - add hev-socks5-tunnel.aar to app/libs (see README).")
            }
            if (!started) return fail("tun2socks failed to start.")
            tunnelStarted = true

            state = State.RUNNING
            updateNotification(if (usesTor) "Routing through Tor + ${chain.size - 1} proxy hop(s)"
                               else "Routing through ${chain.size} proxy hop(s)")
        } catch (e: Exception) {
            Log.e(TAG, "startup failed", e)
            fail("Startup failed: ${e.message}")
        }
    }

    private fun buildTunnelConfig(): String = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_IPV4
          ipv6: '$TUN_IPV6'
        socks5:
          port: $LOCAL_SOCKS_PORT
          address: 127.0.0.1
          udp: 'udp'
        mapdns:
          address: $MAPPED_DNS
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          connect-timeout: 60000
          log-level: warn
    """.trimIndent()

    private fun resolveOutsideVpn(host: String): InetAddress {
        val all = underlyingNetwork?.getAllByName(host) ?: InetAddress.getAllByName(host)
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        stopVpn()
        lastError = message
        state = State.ERROR
        stopSelf()
    }

    // ---- shutdown ----

    private fun stopVpn() {
        synchronized(lock) {
            if (tunnelStarted) {
                try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
                tunnelStarted = false
            }
            chainServer?.stop(); chainServer = null
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
        }
    }

    override fun onDestroy() {
        cancelled = true
        worker?.interrupt()
        stopVpn()
        if (state != State.ERROR) state = State.STOPPED
        super.onDestroy()
    }

    override fun onRevoke() {
        // The user turned the VPN off (or another VPN took over).
        cancelled = true
        stopVpn()
        state = State.STOPPED
        stopSelf()
        super.onRevoke()
    }

    // ---- notification ----

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TorVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Disconnect", stopIntent
                ).build()
            )
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        // The foreground-service type constant only exists/applies on Android 14+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
