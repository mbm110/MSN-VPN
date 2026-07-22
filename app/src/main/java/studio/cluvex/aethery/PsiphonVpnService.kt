package studio.cluvex.aethery

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import ca.psiphon.PsiphonTunnel
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import studio.cluvex.aethery.ConnectionLog

/**
 * PsiphonVpnService — runs Psiphon as a local SOCKS5 proxy (NOT VPN mode)
 * to avoid TUN interface collision with Aethery's VpnService.
 *
 * Threading: ALL Psiphon native calls (start, stop, config) run on a
 * dedicated background thread via Executors. Never block the main thread.
 *
 * Logs: every diagnostic notice is stored and broadcast to MainActivity
 * via ACTION_LOGS. On failure, logcat is dumped into the log buffer.
 */
class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.psiphon.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.psiphon.DISCONNECT"
        const val ACTION_LOGS = "studio.cluvex.aethery.psiphon.LOGS"
        const val EXTRA_LOGS = "logs"
        const val SOCKS_PORT = 10808
        const val HTTP_PORT = 10809
        private const val CHANNEL_ID = "psiphon_vpn"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PsiphonVpn"
    }

    private val bg = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PsiphonBG").apply { isDaemon = true }
    }
    private var tunnel: PsiphonTunnel? = null
    private val isRunning = AtomicBoolean(false)
    private var hasFgService = false
    private val logBuffer = mutableListOf<String>()

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        log("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                log("Connect requested")
                ensureFg("Starting Psiphon…")
                // All native work → background thread
                bg.submit { startTunnelBg() }
            }
            ACTION_DISCONNECT -> {
                log("Disconnect requested")
                // Stop also on background thread (native call!)
                bg.submit { stopTunnel() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log("onDestroy")
        bg.submit { forceStop() }
        bg.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        log("VPN permission revoked")
        bg.submit { stopTunnel() }
    }

    // ── Background tunnel logic ─────────────────────────────────

    private fun startTunnelBg() {
        try {
            log("Creating PsiphonTunnel instance…")
            tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            // VPN mode — Psiphon internally creates VpnService.Builder
            // and sets up TUN routing. protect() in bindToDevice prevents
            // routing loops.
            tunnel?.setVpnMode(true)
            tunnel?.setClientPlatformAffixes("", "")

            log("Calling startTunneling…")
            tunnel?.startTunneling("")
            log("✅ startTunneling returned — Psiphon running in bg")
            broadcastStatus(AetherVpnService.STATUS_CONNECTING)
        } catch (e: Exception) {
            val msg = "❌ ${e.message}"
            log(msg)
            Log.e(TAG, "startTunnel failed", e)
            broadcastStatus(AetherVpnService.STATUS_DISCONNECTED, msg)
            captureLogcat()
            cleanup()
        } catch (t: Throwable) {
            val msg = "💥 Go panic: ${t.message}"
            log(msg)
            Log.e(TAG, "Go panic", t)
            captureLogcat()
            broadcastStatus(AetherVpnService.STATUS_DISCONNECTED, msg)
            cleanup()
        }
    }

    private fun stopTunnel() {
        log("Stopping Psiphon…")
        try {
            tunnel?.stop()
            log("Psiphon stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
        } catch (t: Throwable) {
            Log.e(TAG, "stop Go panic", t)
        }
        cleanup()
    }

    private fun forceStop() {
        try { tunnel?.stop() } catch (_: Throwable) {}
        tunnel = null
        isRunning.set(false)
        if (hasFgService) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            hasFgService = false
        }
    }

    private fun cleanup() {
        tunnel = null
        isRunning.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        if (hasFgService) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            hasFgService = false
        }
        try { stopSelf() } catch (_: Throwable) {}
    }

    // ── PsiphonTunnel.HostService callbacks ────────────────────

    override fun getContext(): Context = this

    override fun getPsiphonConfig(): String = buildConfig()

    override fun loadLibrary(name: String) {
        log("Loading native lib: $name")
        System.loadLibrary(name)
        log("Native lib loaded: $name")
    }

    /**
     * Bind socket to the VPN interface to prevent routing loops.
     * If we're NOT using VPN mode, this is a no-op (still implemented
    )
     */
    override fun bindToDevice(fd: Long) {
        val ok = protect(fd.toInt())
        if (!ok) throw RuntimeException("protect($fd) failed")
        Log.v(TAG, "protect($fd) OK")
    }

    override fun onDiagnosticMessage(message: String) {
        Log.i(TAG, "[Psiphon] $message")
        ConnectionLog.record("[Psiphon] $message")
        synchronized(logBuffer) {
            logBuffer.add("[Psiphon] $message")
            if (logBuffer.size > 200) logBuffer.removeAt(0)
        }
    }

    override fun onConnecting() {
        log("🔄 Connecting…")
        broadcastStatus(AetherVpnService.STATUS_CONNECTING)
    }

    override fun onConnected() {
        log("✅ Connected!")
        isRunning.set(true)
        prefs().edit().putBoolean("vpn_connected", true).apply()
        updateFg("Connected (SOCKS :$SOCKS_PORT)")
        broadcastStatus(AetherVpnService.STATUS_CONNECTED)
    }

    override fun onExiting() {
        log("⬇️ Exiting")
        isRunning.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        broadcastStatus(AetherVpnService.STATUS_DISCONNECTED)
        cleanup()
    }

    // Proxy port callbacks — tell the UI where we're listening
    override fun onListeningSocksProxyPort(port: Int) {
        log("SOCKS proxy ready on port $port")
    }

    override fun onListeningHttpProxyPort(port: Int) {
        log("HTTP proxy ready on port $port")
    }

    override fun onSocksProxyPortInUse(port: Int) {
        log("SOCKS port $port in use (will retry)")
    }

    override fun onHttpProxyPortInUse(port: Int) {
        log("HTTP port $port in use (will retry)")
    }

    // ── Config JSON (SOCKS proxy mode) ─────────────────────────

    private fun buildConfig(): String {
        val config = JSONObject()
        try {
            val base = assets.open("psiphon_config.json").bufferedReader().use { it.readText() }
            val baseJson = JSONObject(base)
            val keys = baseJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                config.put(k, baseJson.get(k))
            }
        } catch (_: Exception) {}

        // Core fields
        if (!config.has("SponsorId")) config.put("SponsorId", "FFFFFFFFFFFFFFFF")
        if (!config.has("PropagationChannelId")) config.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        if (!config.has("ClientPlatform")) config.put("ClientPlatform", "Android")
        if (!config.has("ClientVersion")) config.put("ClientVersion", "1")

        // Local SOCKS/HTTP proxy ports (NOT VPN mode)
        config.put("LocalSocksProxyPort", SOCKS_PORT)
        config.put("LocalHttpProxyPort", HTTP_PORT)

        // Data directory — MUST exist
        val dataDir = File(filesDir, "psiphon_data")
        dataDir.mkdirs()
        config.put("DataRootDirectory", dataDir.absolutePath)

        // Diagnostic logging
        config.put("EmitDiagnosticNotices", true)
        config.put("EmitDiagnosticNetworkParameters", true)
        config.put("EmitBytesTransferred", true)
        config.put("EmitServerAlerts", true)

        // DNS
        config.put("DNSResolverAlternateServers", JSONArray().apply {
            put("1.1.1.1"); put("1.0.0.1")
            put("8.8.8.8"); put("8.8.4.4")
        })

        // No timeout
        config.put("EstablishTunnelTimeoutSeconds", 0)

        return config.toString()
    }

    // ── Logging (thread-safe, in-memory ring buffer) ───────────

    private fun log(msg: String) {
        ConnectionLog.record(msg)
        synchronized(logBuffer) {
            logBuffer.add(msg)
            if (logBuffer.size > 200) logBuffer.removeAt(0)
        }
        Log.d(TAG, msg)
    }

    private fun broadcastLogs() {
        synchronized(logBuffer) {
            val full = logBuffer.joinToString("\n")
            sendBroadcast(Intent(ACTION_LOGS).apply {
                putExtra(EXTRA_LOGS, full)
                `package` = packageName
            })
        }
    }

    private fun captureLogcat() {
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "-s",
                    "PsiphonVpn:V", "PsiphonVpnService:V", "GoLog:V", "*:E")
            )
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                synchronized(logBuffer) { logBuffer.add("[LOGCAT] $line") }
            }
            reader.close()
            broadcastLogs()
        } catch (e: Exception) {
            Log.e(TAG, "captureLogcat failed: $e")
        }
    }

    private fun broadcastStatus(status: String, detail: String? = null) {
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply {
            putExtra(AetherVpnService.EXTRA_STATUS, status)
            if (detail != null) putExtra(AetherVpnService.EXTRA_DETAIL, detail)
            `package` = packageName
        })
    }

    // ── Foreground notification ────────────────────────────────

    private fun ensureFg(text: String) {
        try {
            val n = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            hasFgService = true
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun updateFg(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Psiphon Proxy", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PsiphonVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status_shield)
            .setContentTitle("Psiphon")
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    // ── Unused stubs ───────────────────────────────────────────

    override fun onClientRegion(region: String) {}
    override fun onClientAddress(address: String) {}
    override fun onConnectedServerRegion(region: String) {}
    override fun onBytesTransferred(sent: Long, received: Long) {
        prefs().edit()
            .putLong("total_rx", received).putLong("total_tx", sent)
            .putLong("live_rx", received).putLong("live_tx", sent)
            .apply()
    }
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}
    override fun onListeningSocksProxyUnixPath(path: String) {}
    override fun onListeningHttpProxyUnixPath(path: String) {}
    override fun onUpstreamProxyError(message: String) { log("⚠️ Upstream: $message") }
    override fun onHomepage(url: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onSplitTunnelRegions(regions: MutableList<String>) {}
    override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() { log("⏳ Waiting for network…") }
    override fun onStoppedWaitingForNetworkConnectivity() { log("✅ Network restored") }
    override fun onActiveAuthorizationIDs(ids: MutableList<String>) {}
    override fun onTrafficRateLimits(up: Long, down: Long) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(msg: String, reason: String, regions: MutableList<String>) {}
    override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(e: Int, p: Int, b: Int, t: Long, s: Long,
        es: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>,
        ps: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}
}
