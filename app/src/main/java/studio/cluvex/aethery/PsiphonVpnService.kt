import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock

/**
 * PsiphonVpnService — proxy mode only (setVpnMode=false).
 * Provides SOCKS5 proxy for per-app routing.
 * Traffic monitoring, speed notification, IP fetch through proxy.
 */
class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.psiphon.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.psiphon.DISCONNECT"
        const val ACTION_IP_RESULT = "studio.cluvex.aethery.psiphon.IP_RESULT"
        const val EXTRA_IP = "ip"
        const val EXTRA_COUNTRY = "country"
        const val SOCKS_PORT = 10808
        const val HTTP_PORT = 10809
        private const val CHANNEL_ID = "psiphon_vpn"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PsiphonVpn"
        private const val SETTINGS = "settings"
    }

    private val bg = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PsiphonBG").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "PsiphonStats").apply { isDaemon = true }
    }
    private var tunnel: PsiphonTunnel? = null
    private val isRunning = AtomicBoolean(false)
    private var hasFgService = false
    private var killSwitchEnabled = false
    private val logBuffer = mutableListOf<String>()

    // Traffic monitoring
    private var trafficWatcher: ScheduledFuture<*>? = null
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var sessionStartMs = 0L

    override fun onCreate() { super.onCreate(); createChannel(); log("Service created") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> { log("Connect requested"); ensureFg("Starting…"); bg.submit { startTunnelBg() } }
            ACTION_DISCONNECT -> { log("Disconnect requested"); bg.submit { stopTunnel() } }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { log("onDestroy"); bg.submit { forceStop() }; bg.shutdown(); scheduler.shutdown(); super.onDestroy() }
    override fun onRevoke() { log("VPN revoked"); bg.submit { stopTunnel() } }

    // ── Tunnel logic ───────────────────────────────────────────

    private fun startTunnelBg() {
        try {
            tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            killSwitchEnabled = prefs().getBoolean("kill_switch", false)
            tunnel?.setVpnMode(false) // Proxy mode — let Rust core handle TUN later
            tunnel?.setClientPlatformAffixes("", "")
            val serverEntries = try {
                assets.open("server_entries.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) { log("⚠️ No server_entries.txt: ${e.message}"); "" }
            log("📋 ${serverEntries.length} bytes loaded")
            tunnel?.startTunneling(serverEntries)
            log("✅ startTunneling returned")
            broadcastStatus(AetherVpnService.STATUS_CONNECTING)
        } catch (e: Exception) {
            val msg = "❌ ${e.message}"; log(msg); Log.e(TAG, "startTunnel failed", e)
            broadcastStatus(AetherVpnService.STATUS_DISCONNECTED, msg); captureLogcat(); cleanup()
        } catch (t: Throwable) {
            val msg = "💥 Go panic: ${t.message}"; log(msg); Log.e(TAG, "Go panic", t)
            captureLogcat(); broadcastStatus(AetherVpnService.STATUS_DISCONNECTED, msg); cleanup()
        }
    }

    private fun stopTunnel() { log("Stopping…"); stopTrafficWatcher(); try { tunnel?.stop() } catch (_: Exception) { }; cleanup() }
    private fun forceStop() { stopTrafficWatcher(); try { tunnel?.stop() } catch (_: Throwable) {}; tunnel = null; isRunning.set(false); removeFg() }

    private fun cleanup() {
        stopTrafficWatcher(); tunnel = null; isRunning.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply(); removeFg(); try { stopSelf() } catch (_: Throwable) {}
    }

    // ── Psiphon callbacks ──────────────────────────────────────

    override fun getContext(): Context = this
    override fun getPsiphonConfig(): String = buildConfig()
    override fun loadLibrary(name: String) { log("Loading native lib: $name"); System.loadLibrary(name) }

    override fun bindToDevice(fd: Long) {
        val ok = protect(fd.toInt())
        if (!ok) throw RuntimeException("protect($fd) failed")
    }

    override fun onDiagnosticMessage(message: String) {
        Log.i(TAG, "[Psiphon] $message"); ConnectionLog.record("[Psiphon] $message")
        synchronized(logBuffer) { logBuffer.add("[Psiphon] $message"); if (logBuffer.size > 200) logBuffer.removeAt(0) }
        try {
            val notice = org.json.JSONObject(message)
            when (notice.optString("noticeType", "")) {
                "ListeningSocksProxyPort" -> log("✅ SOCKS proxy on :${notice.optInt("port", 10808)}")
                "Tunnels" -> { val c = notice.optInt("count", 0); if (c > 0) log("✅ $c tunnel(s)") }
                "ConnectingServer" -> log("🔌 Connecting…")
            }
        } catch (_: org.json.JSONException) {}
    }

    override fun onConnecting() { log("🔄 Connecting…"); broadcastStatus(AetherVpnService.STATUS_CONNECTING) }

    override fun onConnected() {
        log("✅ Connected!")
        isRunning.set(true)
        sessionStartMs = SystemClock.elapsedRealtime()
        prefs().edit().putBoolean("vpn_connected", true)
            .putLong("session_start", sessionStartMs).apply()
        broadcastStatus(AetherVpnService.STATUS_CONNECTED)
        startTrafficWatcher()
        // Fetch IP through Psiphon SOCKS proxy (so we see the proxy's exit IP + flag)
        bg.submit { Thread.sleep(2000); fetchPublicIpBg() }
    }

    override fun onExiting() {
        log("⬇️ Exiting"); stopTrafficWatcher(); isRunning.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        broadcastStatus(AetherVpnService.STATUS_DISCONNECTED); cleanup()
        val autoReconnect = prefs().getBoolean("auto_reconnect", false)
        if (autoReconnect && tunnel == null) { log("🔄 Auto-reconnect in 3s…"); bg.submit { Thread.sleep(3000); if (tunnel == null) startTunnelBg() } }
    }

    override fun onListeningSocksProxyPort(port: Int) { log("SOCKS :$port") }
    override fun onListeningHttpProxyPort(port: Int) { log("HTTP :$port") }
    override fun onSocksProxyPortInUse(port: Int) { log("SOCKS $port in use") }
    override fun onHttpProxyPortInUse(port: Int) { log("HTTP $port in use") }
    override fun onBytesTransferred(sent: Long, received: Long) {}

    // ── Traffic monitoring ─────────────────────────────────────

    private fun startTrafficWatcher() {
        stopTrafficWatcher()
        lastRxBytes = currentRxBytes(); lastTxBytes = currentTxBytes()
        prefs().edit().putLong("live_rx", 0L).putLong("live_tx", 0L).apply()
        trafficWatcher = scheduler.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            val rx = currentRxBytes(); val tx = currentTxBytes()
            val deltaRx = (rx - lastRxBytes).coerceAtLeast(0L)
            val deltaTx = (tx - lastTxBytes).coerceAtLeast(0L)
            lastRxBytes = rx; lastTxBytes = tx
            val prefs = prefs()
            val cumRx = prefs.getLong("total_rx", 0) + deltaRx
            val cumTx = prefs.getLong("total_tx", 0) + deltaTx
            prefs.edit().putLong("live_rx", prefs.getLong("live_rx", 0) + deltaRx)
                .putLong("live_tx", prefs.getLong("live_tx", 0) + deltaTx)
                .putLong("total_rx", cumRx).putLong("total_tx", cumTx).apply()
            val timer = formatDuration((now - sessionStartMs) / 1000)
            val ks = if (killSwitchEnabled) " ⛔KS" else ""
            updateNotification("↓${formatRate(deltaRx)}  ↑${formatRate(deltaTx)}  $timer$ks")
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopTrafficWatcher() { try { trafficWatcher?.cancel(true) } catch (_: Exception) {}; trafficWatcher = null }

    // ── IP fetch through Psiphon SOCKS proxy ────────────────────

    private fun fetchPublicIpBg() {
        try {
            // Route through Psiphon's local SOCKS proxy so we see the exit IP
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT))
            val ip = socksHttpGet("https://api.ipify.org?format=json", proxy)?.let { json ->
                Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            }
            val country = if (ip != null) {
                socksHttpGet("https://ip-api.com/json/$ip?fields=countryCode", proxy)?.let { json ->
                    Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                }
            } else null
            val intent = Intent(ACTION_IP_RESULT).apply {
                putExtra(EXTRA_IP, ip ?: ""); putExtra(EXTRA_COUNTRY, country ?: "")
                `package` = packageName
            }
            sendBroadcast(intent)
            log("🌐 IP fetch: $ip / $country")
        } catch (e: Exception) {
            log("⚠️ IP fetch failed: ${e.message}")
        }
    }

    private fun socksHttpGet(url: String, proxy: Proxy): String? {
        try {
            val conn = URL(url).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { Log.v(TAG, "socksHttpGet failed: $url $e"); return null }
    }

    // ── Config JSON ────────────────────────────────────────────

    private fun buildConfig(): String {
        val config = JSONObject()
        try {
            val base = assets.open("psiphon_config.json").bufferedReader().use { it.readText() }
            val baseJson = JSONObject(base)
            val keys = baseJson.keys()
            while (keys.hasNext()) { val k = keys.next(); config.put(k, baseJson.get(k)) }
        } catch (_: Exception) {}

        if (!config.has("SponsorId")) config.put("SponsorId", "FFFFFFFFFFFFFFFF")
        if (!config.has("PropagationChannelId")) config.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        if (!config.has("ClientPlatform")) config.put("ClientPlatform", "Android")
        if (!config.has("ClientVersion")) config.put("ClientVersion", "1")

        config.put("RemoteServerListSignaturePublicKey", "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=")
        config.put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=")
        config.put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=")

        config.put("LocalSocksProxyPort", SOCKS_PORT)
        config.put("LocalHttpProxyPort", HTTP_PORT)

        val dataDir = File(filesDir, "psiphon_data"); dataDir.mkdirs()
        config.put("DataRootDirectory", dataDir.absolutePath)

        config.put("EmitDiagnosticNotices", true)
        config.put("EmitDiagnosticNetworkParameters", true)
        config.put("EmitBytesTransferred", true)
        config.put("EmitServerAlerts", true)
        config.put("EstablishTunnelTimeoutSeconds", 0)

        return config.toString()
    }

    // ── Notification ───────────────────────────────────────────

    private fun ensureFg(text: String) {
        try {
            val n = notification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
            hasFgService = true
        } catch (e: Throwable) { Log.e(TAG, "startForeground", e) }
    }

    private fun updateNotification(text: String) {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) } catch (_: Exception) {}
    }

    private fun removeFg() { if (hasFgService) { try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}; hasFgService = false } }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Psiphon", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(content: String): Notification {
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, PsiphonVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_vpn_status_shield)
            .setContentTitle(getString(R.string.app_name)).setContentText(content).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent).build()
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun currentRxBytes(): Long = trafficBytes(TrafficStats.getTotalRxBytes())
    private fun currentTxBytes(): Long = trafficBytes(TrafficStats.getTotalTxBytes())
    private fun trafficBytes(bytes: Long): Long = if (bytes == TrafficStats.UNSUPPORTED.toLong()) 0L else bytes

    private fun formatRate(bps: Long): String = when { bps < 1_024 -> "$bps B/s"; bps < 1_048_576 -> "${bps / 1_024} KB/s"; else -> "${bps / 1_048_576} MB/s" }
    private fun formatDuration(seconds: Long): String { val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s) }

    private fun log(msg: String) { ConnectionLog.record(msg); synchronized(logBuffer) { logBuffer.add(msg); if (logBuffer.size > 200) logBuffer.removeAt(0) }; Log.d(TAG, msg) }

    private fun captureLogcat() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-s", "PsiphonVpn:V", "GoLog:V", "*:E"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?; while (reader.readLine().also { line = it } != null) { synchronized(logBuffer) { logBuffer.add("[LOGCAT] $line") } }
            reader.close()
        } catch (e: Exception) { Log.e(TAG, "captureLogcat: $e") }
    }

    private fun broadcastStatus(status: String, detail: String? = null) {
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply { putExtra(AetherVpnService.EXTRA_STATUS, status); if (detail != null) putExtra(AetherVpnService.EXTRA_DETAIL, detail); `package` = packageName })
    }

    private fun prefs() = getSharedPreferences(SETTINGS, MODE_PRIVATE)

    // ── Unused stubs ───────────────────────────────────────────
    override fun onClientRegion(region: String) {}; override fun onClientAddress(address: String) {}; override fun onConnectedServerRegion(region: String) {}
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}; override fun onListeningSocksProxyUnixPath(path: String) {}; override fun onListeningHttpProxyUnixPath(path: String) {}
    override fun onUpstreamProxyError(message: String) { log("⚠️ Upstream: $message") }; override fun onHomepage(url: String) {}; override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}; override fun onSplitTunnelRegions(regions: MutableList<String>) {}; override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() { log("⏳ Waiting for network…") }; override fun onStoppedWaitingForNetworkConnectivity() { log("✅ Network restored") }
    override fun onActiveAuthorizationIDs(ids: MutableList<String>) {}; override fun onTrafficRateLimits(up: Long, down: Long) {}; override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(msg: String, reason: String, regions: MutableList<String>) {}; override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(e: Int, p: Int, b: Int, t: Long, s: Long, es: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>, ps: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}
}
