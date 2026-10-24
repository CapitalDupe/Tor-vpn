package com.yourname.torproxychain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to the Orbot app (org.torproject.android) through its published intent API
 * instead of bundling a Tor binary. Orbot's default SOCKS5 port is 9050.
 */
object OrbotManager {
    const val ORBOT_PACKAGE = "org.torproject.android"
    const val DEFAULT_SOCKS_PORT = 9050

    // Orbot Intent API (https://dev.guardianproject.info/projects/orbot/wiki/Orbot_Intent_API)
    private const val ACTION_START = "org.torproject.android.intent.action.START"
    private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
    private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
    private const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"

    fun isOrbotInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Ask Orbot to start Tor in the background. Orbot replies with a STATUS broadcast. */
    fun requestStartTor(ctx: Context) {
        val intent = Intent(ACTION_START)
            .setPackage(ORBOT_PACKAGE)
            .putExtra(EXTRA_PACKAGE_NAME, ctx.packageName)
        ctx.sendBroadcast(intent)
    }

    /** True if something is listening on Orbot's SOCKS port. Never call from the main thread. */
    fun isSocksUp(port: Int = DEFAULT_SOCKS_PORT): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Starts Tor via Orbot and blocks until Orbot reports it is ON (or the timeout passes).
     * Call from a background thread only.
     */
    fun ensureTorRunning(ctx: Context, timeoutMs: Long): Boolean {
        if (!isOrbotInstalled(ctx)) return false

        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra(EXTRA_STATUS) == "ON") latch.countDown()
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_EXPORTED
        )
        try {
            requestStartTor(ctx)
            val ready = try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return false
            }
            // Fallback for Orbot versions that don't answer: accept an already-open SOCKS port.
            return ready || isSocksUp()
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    fun openOrbotInstallPage(ctx: Context) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse("https://guardianproject.info/apps/org.torproject.android/")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
