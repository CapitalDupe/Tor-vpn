package com.yourname.torproxychain

import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local SOCKS5 server on 127.0.0.1. The tun2socks component (hev-socks5-tunnel) forwards all
 * device traffic here, and for every connection we tunnel through the configured chain:
 *
 *   hop[0] -> hop[1] -> ... -> hop[n-1] -> destination
 *
 * Only CONNECT (TCP) is implemented. UDP is not relayed (DNS is answered by hev's "mapdns",
 * so apps still resolve names; QUIC/other UDP simply falls back to TCP or fails).
 */
class ProxyChainSocksServer(
    private val listenPort: Int,
    private val getChain: () -> List<ProxyHop>,
    /** VpnService.protect(): keeps our outbound sockets out of the tunnel (prevents a loop). */
    private val protectSocket: (Socket) -> Boolean,
    /** Name lookup that must NOT go through the VPN (see TorVpnService). */
    private val resolve: (String) -> InetAddress = { InetAddress.getByName(it) },
    private val onLog: (String) -> Unit = {}
) {
    private companion object {
        // Building a Tor circuit can take a while, so negotiation timeouts are generous.
        const val NEGOTIATE_TIMEOUT_MS = 60_000
        const val CONNECT_TIMEOUT_MS = 20_000
        val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val open = ConcurrentHashMap.newKeySet<Socket>()

    /** Binds synchronously so a port conflict is reported to the caller. */
    @Throws(IOException::class)
    fun start() {
        if (running.getAndSet(true)) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 128)
            serverSocket = ss
            pool.execute { acceptLoop(ss) }
            onLog("Local SOCKS5 chain server listening on 127.0.0.1:$listenPort")
        } catch (e: IOException) {
            running.set(false)
            throw e
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        open.toList().forEach { closeQuietly(it) }
        pool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val client = ss.accept()
                open.add(client)
                pool.execute { handleClient(client) }
            } catch (e: Exception) {
                if (running.get()) onLog("Accept failed: ${e.message}")
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        var relayed = false
        try {
            client.soTimeout = NEGOTIATE_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            // --- SOCKS5 greeting from the local tunnel ---
            if (input.readUnsignedByte() != 0x05) return
            val nMethods = input.readUnsignedByte()
            input.readFully(ByteArray(nMethods))
            output.write(byteArrayOf(0x05, 0x00)) // no auth
            output.flush()

            // --- request ---
            input.readUnsignedByte() // version
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // reserved
            val destHost: String = when (input.readUnsignedByte()) {
                0x01 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!!
                0x03 -> {
                    val len = input.readUnsignedByte()
                    String(ByteArray(len).also { input.readFully(it) }, Charsets.UTF_8)
                }
                0x04 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
                else -> { replyFailure(output, 0x08); return } // address type not supported
            }
            val destPort = input.readUnsignedShort()

            if (cmd != 0x01) { replyFailure(output, 0x07); return } // only CONNECT

            val up: Socket = try {
                connectThroughChain(getChain(), destHost, destPort)
            } catch (e: Exception) {
                onLog("Chain connect failed for $destHost:$destPort -> ${e.message}")
                replyFailure(output, 0x05) // connection refused
                return
            }
            upstream = up
            open.add(up)

            // Handshake over: from here on connections may legitimately sit idle.
            client.soTimeout = 0
            up.soTimeout = 0

            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            relayed = true
            relay(client, up)
        } catch (e: Exception) {
            onLog("Client handling error: ${e.message}")
        } finally {
            if (!relayed) {
                closeQuietly(client)
                upstream?.let { closeQuietly(it) }
            }
        }
    }

    /** Opens a socket to the first hop, then CONNECTs hop by hop to the final destination. */
    private fun connectThroughChain(chain: List<ProxyHop>, destHost: String, destPort: Int): Socket {
        var sock = Socket()
        try {
            // Must happen before connect(), or the socket is captured by our own VPN.
            if (!protectSocket(sock)) throw IOException("VpnService.protect() failed")
            sock.keepAlive = true
            sock.soTimeout = NEGOTIATE_TIMEOUT_MS

            if (chain.isEmpty()) {
                sock.connect(InetSocketAddress(resolve(destHost), destPort), CONNECT_TIMEOUT_MS)
                return sock
            }

            val first = chain.first()
            sock.connect(InetSocketAddress(resolve(first.host), first.port), CONNECT_TIMEOUT_MS)

            // Each hop is asked to CONNECT to the NEXT hop; the last hop connects to the real target.
            for ((i, hop) in chain.withIndex()) {
                val next = chain.getOrNull(i + 1)
                sock = negotiateHop(sock, hop, next?.host ?: destHost, next?.port ?: destPort)
            }
            return sock
        } catch (e: Exception) {
            closeQuietly(sock)
            throw e
        }
    }

    /** Returns the socket to keep using (a TLS wrapper for HTTPS hops, otherwise the same one). */
    private fun negotiateHop(sock: Socket, hop: ProxyHop, targetHost: String, targetPort: Int): Socket =
        when (hop.type) {
            ProxyType.SOCKS5 -> { socks5Connect(sock, hop, targetHost, targetPort); sock }
            ProxyType.HTTP -> { httpConnect(sock, hop, targetHost, targetPort); sock }
            ProxyType.HTTPS -> {
                val tls = wrapTls(sock, hop)
                httpConnect(tls, hop, targetHost, targetPort)
                tls
            }
        }

    private fun wrapTls(sock: Socket, hop: ProxyHop): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(sock, hop.host, hop.port, true) as SSLSocket
        try {
            ssl.soTimeout = NEGOTIATE_TIMEOUT_MS
            ssl.startHandshake()
            // Raw SSLSockets don't check the hostname on their own.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(hop.host, ssl.session)) {
                throw SSLPeerUnverifiedException("Certificate does not match ${hop.host}")
            }
        } catch (e: Exception) {
            closeQuietly(ssl)
            throw e
        }
        return ssl
    }

    private fun socks5Connect(sock: Socket, hop: ProxyHop, targetHost: String, targetPort: Int) {
        val out = DataOutputStream(sock.getOutputStream())
        val inp = DataInputStream(sock.getInputStream())

        val useAuth = !hop.username.isNullOrEmpty()
        out.write(if (useAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        out.flush()

        if (inp.readUnsignedByte() != 0x05) throw IOException("${hop.label} is not a SOCKS5 server")
        when (val method = inp.readUnsignedByte()) {
            0x00 -> Unit
            0x02 -> {
                if (!useAuth) throw IOException("${hop.label} requires a username/password")
                val u = hop.username!!.toByteArray(Charsets.UTF_8)
                val p = (hop.password ?: "").toByteArray(Charsets.UTF_8)
                if (u.size > 255 || p.size > 255) throw IOException("Credentials too long for ${hop.label}")
                out.write(0x01)
                out.write(u.size); out.write(u)
                out.write(p.size); out.write(p)
                out.flush()
                inp.readUnsignedByte() // sub-negotiation version
                if (inp.readUnsignedByte() != 0x00) throw IOException("SOCKS5 auth failed at ${hop.label}")
            }
            else -> throw IOException("No acceptable SOCKS5 auth method at ${hop.label} (0x%02x)".format(method))
        }

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        writeSocksAddress(out, targetHost)
        out.writeShort(targetPort)
        out.flush()

        if (inp.readUnsignedByte() != 0x05) throw IOException("Bad SOCKS5 reply from ${hop.label}")
        val rep = inp.readUnsignedByte()
        inp.readUnsignedByte() // reserved
        val addrLen = when (inp.readUnsignedByte()) {
            0x01 -> 4
            0x03 -> inp.readUnsignedByte()
            0x04 -> 16
            else -> throw IOException("Bad SOCKS5 address type from ${hop.label}")
        }
        inp.readFully(ByteArray(addrLen)) // bound address (skipBytes() may skip fewer bytes than asked)
        inp.readUnsignedShort()           // bound port
        if (rep != 0x00) throw IOException("SOCKS5 CONNECT via ${hop.label} failed, code=$rep")
    }

    private fun httpConnect(sock: Socket, hop: ProxyHop, targetHost: String, targetPort: Int) {
        val out = sock.getOutputStream()
        val inp = sock.getInputStream()
        val authority = if (targetHost.contains(':')) "[$targetHost]:$targetPort" else "$targetHost:$targetPort"

        val sb = StringBuilder()
        sb.append("CONNECT $authority HTTP/1.1\r\n")
        sb.append("Host: $authority\r\n")
        if (!hop.username.isNullOrEmpty()) {
            val cred = Base64.encodeToString(
                "${hop.username}:${hop.password ?: ""}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            sb.append("Proxy-Authorization: Basic $cred\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readLine(inp)
        while (readLine(inp).isNotEmpty()) { /* discard headers */ }
        // "HTTP/1.1 200 Connection established" - the reason phrase is optional, so parse the code.
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (code == null || code !in 200..299) {
            throw IOException("HTTP CONNECT via ${hop.label} failed: $statusLine")
        }
    }

    private fun readLine(inp: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = inp.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun writeSocksAddress(out: DataOutputStream, host: String) {
        val literal = parseIpLiteral(host)
        if (literal != null) {
            out.write(if (literal.size == 4) 0x01 else 0x04)
            out.write(literal)
        } else {
            val b = host.toByteArray(Charsets.UTF_8)
            if (b.isEmpty() || b.size > 255) throw IOException("Invalid hostname: $host")
            out.write(0x03)
            out.write(b.size)
            out.write(b)
        }
    }

    private fun parseIpLiteral(host: String): ByteArray? {
        val h = host.removePrefix("[").removeSuffix("]")
        return try {
            when {
                IPV4_LITERAL.matches(h) && h.split('.').all { it.toInt() in 0..255 } ->
                    InetAddress.getByName(h).address
                h.contains(':') -> InetAddress.getByName(h).address // IPv6 literal, no DNS involved
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun replyFailure(output: DataOutputStream, code: Int) {
        try {
            output.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (_: Exception) {}
    }

    /** Copies both directions, honouring half-close; closes both sockets once both sides are done. */
    private fun relay(a: Socket, b: Socket) {
        val remaining = AtomicInteger(2)

        fun forward(from: Socket, to: Socket) {
            try {
                val inp = from.getInputStream()
                val out = to.getOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
                // Propagate EOF. TLS sockets can't half-close, so fall back to a full close.
                try { to.shutdownOutput() } catch (_: Exception) { closeQuietly(a); closeQuietly(b) }
            } catch (_: Exception) {
                closeQuietly(a); closeQuietly(b)
            } finally {
                if (remaining.decrementAndGet() == 0) { closeQuietly(a); closeQuietly(b) }
            }
        }

        pool.execute { forward(b, a) }
        forward(a, b)
    }

    private fun closeQuietly(s: Socket) {
        open.remove(s)
        try { s.close() } catch (_: Exception) {}
    }
}
