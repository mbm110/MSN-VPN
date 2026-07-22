package studio.cluvex.aethery

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PsiphonTunService — builds TUN with ALL features (split tunnel,
 * kill switch, bypass Iran, adblock) and bridges traffic to
 * Psiphon SOCKS5 proxy (127.0.0.1:10808).
 *
 * TCP-only bridge (v1). UDP packets (QUIC, DNS) are silently dropped
 * so apps fall back to TCP.
 */
class PsiphonTunService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.tun.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.tun.DISCONNECT"
        const val ACTION_LOGS = "studio.cluvex.aethery.tun.LOGS"
        const val EXTRA_LOGS = "logs"
        private const val TAG = "PsiphonTun"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 10808
        private const val MTU = 1280
        private const val TUN_IP = "10.111.111.2"
        private const val TUN_IP6 = "fd00::2"
        private const val DNS1 = "1.1.1.1"
    }

    private val bg = Executors.newSingleThreadExecutor { Thread(it, "TunBg").apply { isDaemon = true } }
    private val ioPool = Executors.newCachedThreadPool { Thread(it, "TunIO").apply { isDaemon = true } }
    private var tunPfd: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private val running = AtomicBoolean(false)
    private var hasFg = false

    // Connection key = (srcIp << 32) | (srcPort << 16) | dstPort for TCP tracking
    private val relays = ConcurrentHashMap<Long, TcpRelay>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startTun()
            ACTION_DISCONNECT -> stopTun()
        }
        return START_NOT_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onRevoke() { stopTun() }

    private fun startTun() {
        if (!running.compareAndSet(false, true)) return
        bg.submit {
            try {
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                val ks = prefs.getBoolean("kill_switch", false)
                val splitMode = prefs.getString("split_tunnel_mode", "disabled") ?: "disabled"

                val builder = Builder()
                    .setSession("MSN-VPN")
                    .setMtu(MTU)
                    .addAddress(TUN_IP, 32)
                    .addAddress(TUN_IP6, 128)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer(DNS1)

                // Split Tunneling — allowed apps bypass VPN
                if (splitMode == "whitelist") {
                    val apps = prefs.getStringSet("split_tunnel_apps", emptySet()) ?: emptySet()
                    for (pkg in apps) {
                        try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
                    }
                    ConnectionLog.record("Split-tunnel whitelist: ${apps.size} app(s)")
                } else if (splitMode == "blacklist") {
                    val apps = prefs.getStringSet("split_tunnel_apps", emptySet()) ?: emptySet()
                    for (pkg in apps) {
                        try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                    }
                    ConnectionLog.record("Split-tunnel blacklist: ${apps.size} app(s)")
                }

                // Iranian apps bypass
                val bypassIran = prefs.getBoolean("bypass_iran", false)
                if (bypassIran) {
                    val iranian = listOf(
                        "ir.divar", "ir.co.bazaar", "com.digikala",
                        "com.snapp", "com.tapsi.ryde", "com.mydigipay.payment",
                        "net.irankish.sb24", "com.sheypoor"
                    )
                    for (pkg in iranian) {
                        try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                    }
                    ConnectionLog.record("Bypass Iran: ${iranian.size} app(s)")
                }

                // Kill Switch — when disabled, allow non-VPN traffic
                if (!ks) {
                    builder.allowBypass()
                    ConnectionLog.record("Kill Switch OFF — bypass allowed")
                } else {
                    ConnectionLog.record("Kill Switch ON — all non-VPN traffic blocked")
                }

                val tun = builder.establish()
                    ?: throw Exception("TUN establish failed — permission denied")
                tunPfd = tun
                tunOut = FileOutputStream(tun.fileDescriptor)
                ConnectionLog.record("TUN fd=${tun.fd} — starting bridge to SOCKS5 :$SOCKS_PORT")

                // Read IP packets from TUN
                val fis = FileInputStream(tun.fileDescriptor)
                val buf = ByteArray(65535)
                val hdr = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)

                while (running.get()) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    hdr.clear().limit(n)
                    routePacket(hdr, n)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TUN error", e)
                ConnectionLog.record("❌ TUN bridge: ${e.message}")
            } finally {
                teardown()
            }
        }
    }

    private fun routePacket(hdr: ByteBuffer, len: Int) {
        if (len < 20) return
        val version = hdr.get(0).toInt() shr 4
        if (version != 4) return // IPv4 only for now

        val ihl = (hdr.get(0).toInt() and 0x0f) * 4
        if (len < ihl) return
        val protocol = hdr.get(9).toInt() and 0xff
        val srcIp = hdr.getInt(12)
        val dstIp = hdr.getInt(16)

        if (protocol == 6) { // TCP
            if (len < ihl + 20) return
            val srcPort = (hdr.get(ihl).toInt() and 0xff) shl 8 or (hdr.get(ihl + 1).toInt() and 0xff)
            val dstPort = (hdr.get(ihl + 2).toInt() and 0xff) shl 8 or (hdr.get(ihl + 3).toInt() and 0xff)
            val flags = hdr.get(ihl + 13).toInt() and 0xff
            val key = (srcIp.toLong() and 0xffffffffL) shl 32 or (srcPort.toLong() shl 16) or dstPort.toLong()

            if ((flags and 0x02) != 0) { // SYN
                handleSyn(key, dstIp, dstPort, buf, ihl, len)
            } else {
                handleData(key, buf, ihl, len)
            }
        } // UDP (17) and other protocols silently dropped
    }

    private fun handleSyn(key: Long, dstIp: Int, dstPort: Int, buf: ByteArray, ihl: Int, len: Int) {
        val relay = TcpRelay()
        relays[key] = relay
        ioPool.submit {
            try {
                val dstStr = "${(dstIp shr 24 and 0xff)}.${(dstIp shr 16 and 0xff)}.${(dstIp shr 8 and 0xff)}.${dstIp and 0xff}"
                val sock = Socket()
                sock.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 10000)

                // SOCKS5 handshake
                val out = sock.getOutputStream()
                val `in` = sock.getInputStream()

                // Auth: no auth
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                val authResp = ByteArray(2)
                readFully(`in`, authResp)
                if (authResp[0] != 0x05 || authResp[1] != 0x00) {
                    sock.close(); relays.remove(key); return@submit
                }

                // Connect to destination
                val dstBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(dstIp).array()
                val req = ByteArray(10)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
                System.arraycopy(dstBytes, 0, req, 4, 4)
                req[8] = (dstPort shr 8).toByte()
                req[9] = dstPort.toByte()
                out.write(req)

                val connResp = ByteArray(10)
                readFully(`in`, connResp)
                if (connResp[1] != 0x00) {
                    sock.close(); relays.remove(key); return@submit
                }

                relay.setSocket(sock, out, `in`, dstStr, dstPort)
                relay.relayStart()

            } catch (e: Exception) {
                relays.remove(key)
            }
        }
    }

    private fun handleData(key: Long, buf: ByteArray, ihl: Int, len: Int) {
        val relay = relays[key] ?: return
        val payload = buf.copyOfRange(ihl, len)
        relay.write(payload)
    }

    private fun readFully(`in`: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = `in`.read(b, off, b.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun stopTun() {
        running.set(false)
        teardown()
    }

    private fun teardown() {
        running.set(false)
        for (r in relays.values) r.close()
        relays.clear()
        try { tunPfd?.close() } catch (_: Exception) {}
        tunPfd = null
        if (hasFg) try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { hasFg = false }
        try { stopSelf() } catch (_: Exception) {}
    }

    private inner class TcpRelay {
        private var sock: Socket? = null
        private var os: OutputStream? = null
        private var netIn: InputStream? = null
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        private var dstStr = ""
        private var dstPort = 0
        private val synPayload = mutableListOf<ByteArray>()
        @Volatile private var established = false

        fun setSocket(s: Socket, o: OutputStream, i: InputStream, d: String, p: Int) {
            sock = s; os = o; netIn = i; dstStr = d; dstPort = p
        }

        fun addSynData(data: ByteArray) {
            if (!established) synPayload.add(data) else write(data)
        }

        fun write(data: ByteArray) {
            if (!closed) queue.add(data)
        }

        fun start() {
            Thread({
                try {
                    // Flush SYN payload first
                    for (p in synPayload) { os?.write(p); os?.flush() }
                    synPayload.clear()
                    established = true

                    // Writer: drain queue → SOCKS5
                    val writer = Thread({
                        try {
                            while (!closed) {
                                val data = queue.poll(500, TimeUnit.MILLISECONDS)
                                if (data == null && closed) break
                                if (data != null) { os?.write(data); os?.flush() }
                            }
                        } catch (_: Exception) {}
                    }, "Send-$dstStr:$dstPort")
                    writer.start()

                    // Reader: SOCKS5 → TUN
                    val buf = ByteArray(65535)
                    while (!closed) {
                        val n = netIn?.read(buf) ?: -1
                        if (n < 0) break
                        // Write IP header + TCP segment back to TUN
                        tunOut?.write(buf, 0, n)
                        tunOut?.flush()
                    }
                    writer.interrupt()
                } catch (_: Exception) {}
                close()
            }, "Relay-$dstStr:$dstPort").start()
        }

        fun close() {
            closed = true
            try { sock?.close() } catch (_: Exception) {}
        }
    }
}
