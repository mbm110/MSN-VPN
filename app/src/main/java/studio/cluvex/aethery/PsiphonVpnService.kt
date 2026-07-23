/**
 * PsiphonVpnService — Pure SOCKS5 proxy mode (no TUN).
 * Used by AetherVpnService as upstream_proxy for full-tunnel mode,
 * or standalone for per-app proxy mode.
 */
package studio.cluvex.aethery

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import ca.psiphon.PsiphonTunnel
import org.json.JSONObject
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
import studio.cluvex.aethery.ConnectionLog

class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.psiphon.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.psiphon.DISCONNECT"
        const val ACTION_READY = "studio.cluvex.aethery.psiphon.READY"
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
    private var hasFgService = false
    private var socksPort = 10808
    private var trafficWatcher: ScheduledFuture<*>? = null
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var sessionStartMs = 0L

    override fun onCreate() { super.onCreate(); createChannel(); log("Created") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                socksPort = intent.getIntExtra(EXTRA_PORT, getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt("default_socks_port", 10808))
                log("Connect requested (port $socksPort)")
                ensureFg("Starting Psiphon…")
                bg.submit { startTunnelBg() }
            }
            ACTION_DISCONNECT -> { log("Disconnect"); bg.submit { stopTunnel() } }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { bg.submit { forceStop() }; bg.shutdown(); scheduler.shutdown(); super.onDestroy() }
    override fun onRevoke() { bg.submit { stopTunnel() } }

    private fun startTunnelBg() {
        try {
            tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            tunnel?.setVpnMode(false)
            tunnel?.setClientPlatformAffixes("", "")
            val serverEntries = try {
                assets.open("server_entries.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) { log("No server_entries.txt: ${e.message}"); "" }
            tunnel?.startTunneling(serverEntries)
            log("startTunneling returned")
        } catch (e: Exception) {
            log("${e.message}"); captureLogcat(); cleanup()
        } catch (t: Throwable) {
            log("Go panic: ${t.message}"); captureLogcat(); cleanup()
        }
    }

    private fun stopTunnel() { stopTrafficWatcher(); try { tunnel?.stop() } catch (_: Exception) {}; cleanup() }
    private fun forceStop() { stopTrafficWatcher(); try { tunnel?.stop() } catch (_: Throwable) {}; tunnel = null; isRunning.set(false); removeFg() }

    private fun cleanup() {
        stopTrafficWatcher(); tunnel = null; isRunning.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply(); removeFg(); try { stopSelf() } catch (_: Throwable) {}
    }

    // ── Psiphon callbacks ──────────────────────────────────────

    override fun getContext(): Context = this
    override fun getPsiphonConfig(): String = buildConfig()
    override fun loadLibrary(name: String) { System.loadLibrary(name) }
    override fun bindToDevice(fd: Long) { val ok = protect(fd.toInt()); if (!ok) throw RuntimeException("protect($fd) failed") }

    override fun onDiagnosticMessage(message: String) {
        Log.i(TAG, "[Psiphon] $message"); ConnectionLog.record("[Psiphon] $message")
        try {
            val notice = JSONObject(message)
            when (notice.optString("noticeType", "")) {
                "ListeningSocksProxyPort" -> {
                    log("SOCKS proxy ready on :${notice.optInt("port", socksPort)}")
                    broadcastReady()
                }
                "Tunnels" -> { val c = notice.optInt("count", 0); if (c > 0) log("$c tunnel(s)") }
                "ConnectingServer" -> log("Connecting…")
            }
        } catch (_: org.json.JSONException) {}
    }

    override fun onConnecting() { log("Connecting…") }

    override fun onConnected() {
        log("Connected!")
        isRunning.set(true)
        sessionStartMs = SystemClock.elapsedRealtime()
        prefs().edit().putBoolean("vpn_connected", true).putLong("session_start", sessionStartMs).apply()
        startTrafficWatcher()
        // Broadcast connected state to MainActivity
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply {
            putExtra(AetherVpnService.EXTRA_STATUS, AetherVpnService.STATUS_CONNECTED)
            `package` = packageName
        })
        // Fetch IP through SOCKS proxy for flag display
        bg.submit { Thread.sleep(2000); fetchPublicIpBg() }
    }

    override fun onExiting() {
        log("Exiting"); stopTrafficWatcher(); isRunning.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        val autoReconnect = prefs().getBoolean("auto_reconnect", false)
        if (autoReconnect && tunnel == null) { bg.submit { Thread.sleep(3000); if (tunnel == null) startTunnelBg() } }
        cleanup()
    }

    override fun onListeningSocksProxyPort(port: Int) { log("SOCKS :$port") }
    override fun onListeningHttpProxyPort(port: Int) { log("HTTP :$port") }
    override fun onBytesTransferred(sent: Long, received: Long) {}

    // ── Broadcast ready for AetherVpnService ────────────────────

    private fun broadcastReady() {
        sendBroadcast(Intent(ACTION_READY).apply {
            putExtra(EXTRA_PORT, socksPort)
            `package` = packageName
        })
    }

    // ── Traffic monitoring ─────────────────────────────────────

    private fun startTrafficWatcher() {
        stopTrafficWatcher()
        lastRxBytes = currentRxBytes(); lastTxBytes = currentTxBytes()
        prefs().edit().putLong("live_rx", 0L).putLong("live_tx", 0L).apply()
        trafficWatcher = scheduler.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            val rx = currentRxBytes(); val tx = currentTxBytes()
            val deltaRx = (rx - lastRxBytes).coerceAtLeast(0L); val deltaTx = (tx - lastTxBytes).coerceAtLeast(0L)
            lastRxBytes = rx; lastTxBytes = tx
            val p = prefs()
            p.edit().putLong("live_rx", p.getLong("live_rx", 0) + deltaRx).putLong("live_tx", p.getLong("live_tx", 0) + deltaTx)
                .putLong("total_rx", p.getLong("total_rx", 0) + deltaRx).putLong("total_tx", p.getLong("total_tx", 0) + deltaTx).apply()
            val timer = formatDuration((now - sessionStartMs) / 1000)
            updateNotification("↓${formatRate(deltaRx)}  ↑${formatRate(deltaTx)}  $timer")
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopTrafficWatcher() { try { trafficWatcher?.cancel(true) } catch (_: Exception) {}; trafficWatcher = null }

    // ── IP fetch through SOCKS proxy ────────────────────────────

    private fun fetchPublicIpBg() {
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val ip = socksGet("https://api.ipify.org?format=json", proxy)?.let { json ->
                Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            }
            val country = if (ip != null) {
                socksGet("https://ip-api.com/json/$ip?fields=countryCode", proxy)?.let { json ->
                    Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                }
            } else null
            sendBroadcast(Intent(ACTION_IP_RESULT).apply {
                putExtra(EXTRA_IP, ip ?: ""); putExtra(EXTRA_COUNTRY, country ?: "")
                `package` = packageName
            })
            log("IP fetch: $ip / $country")
        } catch (e: Exception) { log("IP fetch failed: ${e.message}") }
    }

    private fun socksGet(url: String, proxy: Proxy): String? {
        return try { URL(url).openConnection(proxy).let { (it as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }; it.inputStream.bufferedReader().use { it.readText() } }
        } catch (e: Exception) { Log.v(TAG, "socksGet failed: $url $e"); null }
    }

    // ── Config JSON ────────────────────────────────────────────

    private fun buildConfig(): String {
        val config = JSONObject()
        try {
            val base = assets.open("psiphon_config.json").bufferedReader().use { it.readText() }
            val baseJson = JSONObject(base); val keys = baseJson.keys()
            while (keys.hasNext()) { val k = keys.next(); config.put(k, baseJson.get(k)) }
        } catch (_: Exception) {}

        config.put("SponsorId", "FFFFFFFFFFFFFFFF")
        config.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        config.put("ClientPlatform", "Android")
        config.put("ClientVersion", "1")
        config.put("RemoteServerListSignaturePublicKey", "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=")
        config.put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=")
        config.put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=")
        config.put("LocalSocksProxyPort", socksPort)
        config.put("LocalHttpProxyPort", 0) // Don't need HTTP proxy

        val dataDir = File(filesDir, "psiphon_data"); dataDir.mkdirs()
        config.put("DataRootDirectory", dataDir.absolutePath)
        config.put("EmitDiagnosticNotices", true)
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
        } catch (_: Throwable) {}
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
    private fun log(msg: String) { ConnectionLog.record(msg); Log.d(TAG, msg) }
    private fun captureLogcat() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-s", "PsiphonVpn:V", "GoLog:V", "*:E"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream)); var line: String?
            while (reader.readLine().also { line = it } != null) { ConnectionLog.record("[LOGCAT] $line") }; reader.close()
        } catch (_: Exception) {}
    }
    private fun prefs() = getSharedPreferences(SETTINGS, MODE_PRIVATE)

    // ── Unused stubs ───────────────────────────────────────────
    override fun onClientRegion(region: String) {}; override fun onClientAddress(address: String) {}; override fun onConnectedServerRegion(region: String) {}
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}; override fun onListeningSocksProxyUnixPath(path: String) {}; override fun onListeningHttpProxyUnixPath(path: String) {}
    override fun onUpstreamProxyError(message: String) { log("Upstream: $message") }; override fun onHomepage(url: String) {}; override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}; override fun onSplitTunnelRegions(regions: MutableList<String>) {}; override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() { log("Waiting for network…") }; override fun onStoppedWaitingForNetworkConnectivity() { log("Network restored") }
    override fun onActiveAuthorizationIDs(ids: MutableList<String>) {}; override fun onTrafficRateLimits(up: Long, down: Long) {}; override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(msg: String, reason: String, regions: MutableList<String>) {}; override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(e: Int, p: Int, b: Int, t: Long, s: Long, es: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>, ps: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}
}
