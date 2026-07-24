package studio.cluvex.aethery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import ca.psiphon.PsiphonTunnel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clean Psiphon engine — pure SOCKS5 proxy background service.
 *
 * Architecture:
 * - NOT a VpnService (prevents TUN conflicts)
 * - Runs as a regular foreground Service
 * - Connects to the Internet DIRECTLY (not through the app's TUN)
 * - Provides a local SOCKS5 proxy that AetherVpnService's Rust core uses
 *   as upstream for device traffic
 * - Psiphon's own sockets are protected by AetherVpnService.socketProtector
 *
 * Routing loop prevention:
 * AetherVpnService calls addDisallowedApplication(packageName), so Android
 * never routes PsiphonVpnService's traffic through the TUN. Psiphon reaches
 * the Internet directly. The TUN only captures OTHER apps' traffic.
 *
 * State management:
 * - VpnState: unified connection state (CONNECTED/FAILED/etc.)
 * - Broadcast: IP result for UI flag display
 */
class PsiphonVpnService : Service(), PsiphonTunnel.HostService {

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.psiphon.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.psiphon.DISCONNECT"
        const val ACTION_IP_RESULT = "studio.cluvex.aethery.psiphon.IP_RESULT"
        const val EXTRA_IP = "ip"
        const val EXTRA_COUNTRY = "country"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "psiphon_vpn"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PsiphonVpn"
        private const val SETTINGS = "settings"
    }

    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "PsiphonBG").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PsiphonStats").apply { isDaemon = true } }
    private var tunnel: PsiphonTunnel? = null
    private val isRunning = AtomicBoolean(false)
    private var hasFg = false
    private var socksPort = 10808
    private var trafficJob: ScheduledFuture<*>? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var sessionStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        log("Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                socksPort = intent.getIntExtra(EXTRA_PORT,
                    getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt("default_socks_port", 10808))
                log("Connect requested (port $socksPort)")
                ensureFg("Starting Psiphon…")
                bg.submit { startTunnelBg() }
            }
            ACTION_DISCONNECT -> {
                log("Disconnect requested")
                bg.submit { stopTunnel() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bg.submit { forceStop() }
        bg.shutdown()
        scheduler.shutdown()
        super.onDestroy()
    }

    // ── Psiphon lifecycle ────────────────────────────────────────────

    private fun startTunnelBg() {
        try {
            log("Creating Psiphon tunnel")
            tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            tunnel?.setVpnMode(false)  // Pure proxy, no Android TUN involvement
            tunnel?.setClientPlatformAffixes("", "")
            val serverEntries = try {
                assets.open("server_entries.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                log("No server_entries.txt: ${e.message}"); ""
            }
            tunnel?.startTunneling(serverEntries)
            log("Psiphon tunnel submitted")
        } catch (e: Exception) {
            log("Psiphon start failed: ${e.message}")
            captureLogcat()
            cleanup()
        } catch (t: Throwable) {
            log("Psiphon Go panic: ${t.message}")
            captureLogcat()
            cleanup()
        }
    }

    private fun stopTunnel() {
        trafficJob?.cancel(true)
        trafficJob = null
        try { tunnel?.stop() } catch (_: Exception) {}
        cleanup()
    }

    private fun forceStop() {
        trafficJob?.cancel(true)
        trafficJob = null
        try { tunnel?.stop() } catch (_: Throwable) {}
        tunnel = null
        isRunning.set(false)
        removeFg()
    }

    private fun cleanup() {
        tunnel = null
        isRunning.set(false)
        VpnState.setDisconnected()
        prefs().edit()
            .putBoolean("vpn_connected", false)
            .putBoolean("psiphon_running", false)
            .apply()
        removeFg()
        try { stopSelf() } catch (_: Throwable) {}
    }

    // ── Psiphon HostService callbacks ────────────────────────────────

    override fun getContext(): Context = this

    override fun getPsiphonConfig(): String = buildConfig()

    override fun loadLibrary(name: String) {
        System.loadLibrary(name)
    }

    /**
     * PROTECT Psiphon sockets from the TUN.
     *
     * When AetherVpnService is running, its Builder called
     * addDisallowedApplication(packageName). This means the TUN WON'T
     * capture our traffic — we reach the Internet directly.
     * But if for any reason the TUN DID capture us, this protect()
     * call would exempt us. Double safety.
     */
    override fun bindToDevice(fd: Long) {
        val protector = AetherVpnService.socketProtector
        if (protector != null) {
            val ok = protector.invoke(fd.toInt())
            if (!ok) {
                log("WARNING: protect($fd) failed, Psiphon may route through TUN")
            }
        }
        // If socketProtector is null (Psiphon standalone), don't throw.
        // Psiphon will connect directly via the OS routing table.
    }

    override fun onDiagnosticMessage(message: String) {
        ConnectionLog.record("[Psiphon] $message")
        try {
            val notice = JSONObject(message)
            when (notice.optString("noticeType", "")) {
                "ListeningSocksProxyPort" -> {
                    log("SOCKS proxy ready on :${notice.optInt("port", socksPort)}")
                }
                "Tunnels" -> {
                    val count = notice.optInt("count", 0)
                    if (count > 0) log("$count tunnel(s)")
                }
                "ConnectingServer" -> log("Connecting to server…")
            }
        } catch (_: org.json.JSONException) {}
    }

    override fun onConnecting() {
        log("Connecting…")
    }

    override fun onConnected() {
        log("Connected!")
        isRunning.set(true)
        sessionStartMs = SystemClock.elapsedRealtime()

        prefs().edit()
            .putBoolean("vpn_connected", true)
            .putBoolean("psiphon_running", true)
            .putLong("session_start", sessionStartMs)
            .apply()

        VpnState.setConnected("", "")  // Update unified state
        broadcastStatus(AetherVpnService.STATUS_CONNECTED)
        startTrafficWatch()

        // Fetch IP + country through Psiphon's SOCKS proxy for the UI flag
        bg.submit {
            Thread.sleep(2000)
            fetchPublicIpViaProxy()
        }
    }

    override fun onExiting() {
        log("Exiting")
        trafficJob?.cancel(true)
        trafficJob = null
        isRunning.set(false)

        prefs().edit()
            .putBoolean("vpn_connected", false)
            .apply()

        val autoReconnect = prefs().getBoolean("auto_reconnect", false)
        if (autoReconnect && tunnel == null) {
            bg.submit {
                Thread.sleep(3000)
                if (tunnel == null) {
                    log("Auto-reconnecting Psiphon…")
                    startTunnelBg()
                }
            }
        }
        cleanup()
    }

    override fun onListeningSocksProxyPort(port: Int) {
        log("SOCKS :$port")
        socksPort = port
    }

    override fun onListeningHttpProxyPort(port: Int) {
        log("HTTP :$port")
    }

    override fun onBytesTransferred(sent: Long, received: Long) {}

    // Remaining callbacks (unused but required)
    override fun onClientRegion(region: String) {}
    override fun onClientAddress(address: String) {}
    override fun onConnectedServerRegion(region: String) {
        log("Server region: $region")
    }
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}
    override fun onListeningSocksProxyUnixPath(path: String) {}
    override fun onListeningHttpProxyUnixPath(path: String) {}
    override fun onUpstreamProxyError(message: String) { log("Upstream proxy error: $message") }
    override fun onHomepage(url: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onSplitTunnelRegions(regions: MutableList<String>) {}
    override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() { log("Waiting for network…") }
    override fun onStoppedWaitingForNetworkConnectivity() { log("Network restored") }
    override fun onActiveAuthorizationIDs(ids: MutableList<String>) {}
    override fun onTrafficRateLimits(up: Long, down: Long) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(msg: String, reason: String, regions: MutableList<String>) {}
    override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(e: Int, p: Int, b: Int, t: Long, s: Long,
        es: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>,
        ps: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}

    // ── IP fetch through Psiphon's own SOCKS proxy ─────────────────

    /**
     * Fetch public IP and country through Psiphon's local SOCKS proxy.
     * This correctly shows the exit node's IP/country, not the device's.
     * The SOCKS proxy always goes through Psiphon's tunnel.
     */
    private fun fetchPublicIpViaProxy() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        // Step 1: get IP from ipify
        val ipJson = socksGet("https://api.ipify.org?format=json", proxy)
        log("IP raw: ${ipJson ?: "null"}")
        val ip = parseIp(ipJson)

        // Step 2: get country code from ip-api.com
        val country = if (ip.isNotEmpty()) {
            val countryJson = socksGet("http://ip-api.com/json/$ip?fields=countryCode", proxy)
            log("Country raw: ${countryJson ?: "null"}")
            parseCountry(countryJson)
        } else ""

        // Step 3: update state and broadcast
        VpnState.ip = ip
        VpnState.countryCode = country
        broadcastIpResult(ip, country)
        log("IP fetch: $ip / $country")
    }

    private fun socksGet(url: String, proxy: Proxy): String? {
        return try {
            val conn = URL(url).openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            log("socksGet fail: ${e.message}")
            null
        }
    }

    private fun parseIp(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            val obj = JSONObject(json)
            obj.optString("ip", "").ifEmpty { null } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseCountry(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            val obj = JSONObject(json)
            obj.optString("countryCode", "").ifEmpty { null } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ── Traffic monitoring ─────────────────────────────────────────

    private fun startTrafficWatch() {
        trafficJob?.cancel(true)
        lastRx = currentRx()
        lastTx = currentTx()
        prefs().edit().putLong("live_rx", 0L).putLong("live_tx", 0L).apply()

        trafficJob = scheduler.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            val rx = currentRx()
            val tx = currentTx()
            val deltaRx = (rx - lastRx).coerceAtLeast(0L)
            val deltaTx = (tx - lastTx).coerceAtLeast(0L)
            lastRx = rx
            lastTx = tx

            val p = prefs()
            p.edit()
                .putLong("live_rx", p.getLong("live_rx", 0) + deltaRx)
                .putLong("live_tx", p.getLong("live_tx", 0) + deltaTx)
                .putLong("total_rx", p.getLong("total_rx", 0) + deltaRx)
                .putLong("total_tx", p.getLong("total_tx", 0) + deltaTx)
                .apply()

            val timer = formatDuration((now - sessionStartMs) / 1000)
            updateNotification("↓${formatRate(deltaRx)}  ↑${formatRate(deltaTx)}  $timer")
        }, 1, 1, TimeUnit.SECONDS)
    }

    // ── Broadcast helpers ───────────────────────────────────────────

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(AetherVpnService.EXTRA_STATUS, status)
        })
    }

    private fun broadcastIpResult(ip: String, countryCode: String) {
        sendBroadcast(Intent(ACTION_IP_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_IP, ip)
            putExtra(EXTRA_COUNTRY, countryCode)
        })
    }

    // ── Config ─────────────────────────────────────────────────────

    private fun buildConfig(): String {
        val config = JSONObject()
        try {
            val base = assets.open("psiphon_config.json").bufferedReader().use { it.readText() }
            val baseJson = JSONObject(base)
            baseJson.keys().forEach { config.put(it, baseJson.get(it)) }
        } catch (_: Exception) {}

        config.put("SponsorId", "FFFFFFFFFFFFFFFF")
        config.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        config.put("ClientPlatform", "Android")
        config.put("ClientVersion", "1")
        config.put("RemoteServerListSignaturePublicKey", "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=")
        config.put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=")
        config.put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=")
        config.put("LocalSocksProxyPort", socksPort)
        config.put("LocalHttpProxyPort", 0)  // Don't need HTTP proxy

        val dataDir = File(filesDir, "psiphon_data"); dataDir.mkdirs()
        config.put("DataRootDirectory", dataDir.absolutePath)
        config.put("EmitDiagnosticNotices", true)
        config.put("EmitBytesTransferred", true)
        config.put("EmitServerAlerts", true)
        config.put("EstablishTunnelTimeoutSeconds", 0)

        return config.toString()
    }

    // ── Notification ───────────────────────────────────────────────

    private fun ensureFg(text: String) {
        runCatching {
            val n = notification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            hasFg = true
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            (getSystemService(NotificationManager::class.java) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun removeFg() {
        if (hasFg) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            hasFg = false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NotificationManager::class.java) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Psiphon", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(content: String): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, PsiphonVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun currentRx() = trafficBytes(TrafficStats.getTotalRxBytes())
    private fun currentTx() = trafficBytes(TrafficStats.getTotalTxBytes())
    private fun trafficBytes(b: Long) = if (b == TrafficStats.UNSUPPORTED.toLong()) 0L else b
    private fun formatRate(bps: Long): String = when {
        bps < 1_024 -> "$bps B/s"
        bps < 1_048_576 -> "${bps / 1_024} KB/s"
        else -> "${bps / 1_048_576} MB/s"
    }
    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun log(msg: String) {
        ConnectionLog.record(msg)
        Log.d(TAG, msg)
    }

    private fun captureLogcat() {
        runCatching {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "-s", "PsiphonVpn:V", "GoLog:V", "*:E"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            reader.forEachLine { ConnectionLog.record("[LOGCAT] $it") }
            reader.close()
        }
    }

    private fun prefs() = getSharedPreferences(SETTINGS, MODE_PRIVATE)
}