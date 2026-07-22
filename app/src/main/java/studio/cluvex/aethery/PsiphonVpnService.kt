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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground VpnService hosting Psiphon tunnel.
 *
 * ARCHITECTURE (simplified — no tun2socks):
 *  - This service extends VpnService and provides the Android VPN context.
 *  - Psiphon tunnel-core in VPN mode creates its own TUN interface internally
 *    using our VpnService.Builder context.
 *  - bindToDevice() → VpnService.protect(fd) prevents Psiphon's sockets from
 *    looping back through the VPN TUN.
 *  - We DO NOT create a separate TUN ourselves (Psiphon handles it).
 *
 * CRITICAL: without protect() in bindToDevice(), the Go runtime panics
 *           immediately because its sockets enter a routing loop.
 */
class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    private var tunnel: PsiphonTunnel? = null
    private val isConnected = AtomicBoolean(false)
    private var hasFgService = false
    private var tunnelThread: Thread? = null
    private val events = mutableListOf<String>()   // In-memory event log

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        logEvent("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                logEvent("Connect requested")
                ensureFg("Starting Psiphon…")
                tunnelThread = Thread { startTunnel() }.apply {
                    name = "PsiphonInit"
                    start()
                }
            }
            ACTION_DISCONNECT -> {
                logEvent("Disconnect requested")
                stopTunnel()
            }
            ACTION_GET_LOGS -> {
                // Return accumulated logs via broadcast
                sendLogs()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logEvent("onDestroy")
        stopTunnelInternal()
        super.onDestroy()
    }

    override fun onRevoke() {
        logEvent("VPN permission revoked")
        stopTunnel()
    }

    // ── Tunnel startup (background thread) ─────────────────────

    private fun startTunnel() {
        try {
            logEvent("Creating PsiphonTunnel…")
            tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            tunnel?.setClientPlatformAffixes("", "")
            tunnel?.setVpnMode(true)
            logEvent("Starting tunnel (this calls Psi.start → Go runtime)…")

            // NOTE: startTunneling loads 30MB libgojni.so + init Go runtime.
            // If protect() is missing in bindToDevice, Go panics immediately.
            tunnel?.startTunneling("")
            logEvent("startTunneling returned — Psiphon running in background")
            sendStatus(AetherVpnService.STATUS_CONNECTING)
        } catch (e: Exception) {
            val msg = "Psiphon error: ${e.message}"
            logEvent("❌ $msg")
            Log.e(TAG, msg, e)
            sendStatus(AetherVpnService.STATUS_DISCONNECTED, msg)
            // Capture fallback logcat
            captureLogcat()
            stopTunnelInternal()
        } catch (t: Throwable) {
            // Go panic or UnsatisfiedLinkError falls here
            val msg = "Psiphon FATAL: ${t.message}"
            logEvent("💥 $msg")
            Log.e(TAG, msg, t)
            captureLogcat()
            sendStatus(AetherVpnService.STATUS_DISCONNECTED, msg)
            stopTunnelInternal()
        }
    }

    // ── PsiphonTunnel.HostService ──────────────────────────────

    override fun getContext(): Context = this

    override fun getPsiphonConfig(): String = buildConfig()

    override fun loadLibrary(name: String) {
        logEvent("Loading native lib: $name")
        System.loadLibrary(name)
        logEvent("Native lib loaded: $name")
    }

    /**
     * CRITICAL: Called for EVERY socket Psiphon opens.
     * Must bypass the VPN routing loop — without this, Go runtime panics.
     * Match MahsaNG: throw RuntimeException on failure so tunnel-core retries.
     */
    override fun bindToDevice(fd: Long) {
        val result = protect(fd.toInt())
        if (!result) {
            throw RuntimeException("protect($fd) failed")
        }
        Log.v(TAG, "protect($fd) OK")
    }

    override fun onDiagnosticMessage(message: String) {
        Log.i(TAG, "[PsiphonDiag] $message")
        // Collect diagnostic notices for the event log
        synchronized(events) {
            events.add("[Psiphon] $message")
        }
    }

    override fun onConnecting() {
        logEvent("🔄 Psiphon connecting…")
        sendStatus(AetherVpnService.STATUS_CONNECTING)
    }

    override fun onConnected() {
        logEvent("✅ Psiphon connected!")
        isConnected.set(true)
        prefs().edit().putBoolean("vpn_connected", true).apply()
        updateFg("Psiphon Connected")
        sendStatus(AetherVpnService.STATUS_CONNECTED)
    }

    override fun onExiting() {
        logEvent("⬇️ Psiphon exiting")
        isConnected.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        sendStatus(AetherVpnService.STATUS_DISCONNECTED)
        stopTunnelInternal()
    }

    override fun onClientRegion(region: String) {}
    override fun onClientAddress(address: String) {}
    override fun onConnectedServerRegion(region: String) {}

    override fun onBytesTransferred(sent: Long, received: Long) {
        prefs().edit()
            .putLong("total_rx", received)
            .putLong("total_tx", sent)
            .putLong("live_rx", received)
            .putLong("live_tx", sent)
            .apply()
    }

    // Stubs
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}
    override fun onSocksProxyPortInUse(port: Int) {}
    override fun onHttpProxyPortInUse(port: Int) {}
    override fun onListeningSocksProxyPort(port: Int) {}
    override fun onListeningHttpProxyPort(port: Int) {}
    override fun onListeningSocksProxyUnixPath(path: String) {}
    override fun onListeningHttpProxyUnixPath(path: String) {}
    override fun onUpstreamProxyError(message: String) { logEvent("⚠️ Upstream: $message") }
    override fun onHomepage(url: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onSplitTunnelRegions(regions: MutableList<String>) {}
    override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() { logEvent("⏳ Waiting for network…") }
    override fun onStoppedWaitingForNetworkConnectivity() { logEvent("✅ Network restored") }
    override fun onActiveAuthorizationIDs(ids: MutableList<String>) {}
    override fun onTrafficRateLimits(up: Long, down: Long) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(msg: String, reason: String, regions: MutableList<String>) {}
    override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(egressNodeCount: Int, proxyNodeCount: Int, brokerCount: Int, totalTransferredBytes: Long, totalConnectedSeconds: Long, icEgressSnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>, icProxySnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}

    // ── Config builder ─────────────────────────────────────────

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

        // Core required fields (MahsaNG format)
        if (!config.has("SponsorId"))
            config.put("SponsorId", "FFFFFFFFFFFFFFFF")
        if (!config.has("PropagationChannelId"))
            config.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        if (!config.has("ClientPlatform"))
            config.put("ClientPlatform", "Android")
        if (!config.has("ClientVersion"))
            config.put("ClientVersion", "1")

        // Data directory — MUST exist before Go touches it
        val dataDir = File(filesDir, "psiphon_data")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            logEvent("Created data dir: ${dataDir.absolutePath}")
        }
        config.put("DataRootDirectory", dataDir.absolutePath)

        // Diagnostic logging (enables onDiagnosticMessage callbacks)
        config.put("EmitDiagnosticNotices", true)
        config.put("EmitDiagnosticNetworkParameters", true)
        config.put("EmitBytesTransferred", true)
        config.put("EmitServerAlerts", true)

        // Alternate DNS (matches mahsaNG)
        config.put("DNSResolverAlternateServers", JSONArray().apply {
            put("1.1.1.1")
            put("1.0.0.1")
            put("8.8.8.8")
            put("8.8.4.4")
        })

        // No timeout — keep trying
        config.put("EstablishTunnelTimeoutSeconds", 0)

        val result = config.toString()
        logEvent("Config: $result")
        return result
    }

    // ── In-app event log ───────────────────────────────────────

    private fun logEvent(msg: String) {
        synchronized(events) {
            events.add(msg)
        }
        Log.d(TAG, msg)
    }

    /** Fallback: grab logcat lines if Psiphon crashed */
    private fun captureLogcat() {
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "-s", "PsiphonVpn:V", "PsiphonVpnService:V", "GoLog:V", "*:E")
            )
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                synchronized(events) { events.add("[LOGCAT] $line") }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "captureLogcat failed: ${e.message}")
        }
    }

    /** Broadcast accumulated logs to MainActivity */
    private fun sendLogs() {
        synchronized(events) {
            val sb = StringBuilder()
            for (e in events.takeLast(50)) {
                sb.append(e).append('\n')
            }
            sendBroadcast(Intent(ACTION_LOGS).apply {
                putExtra("logs", sb.toString())
                `package` = packageName
            })
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun sendStatus(status: String, detail: String? = null) {
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply {
            putExtra(AetherVpnService.EXTRA_STATUS, status)
            if (detail != null) putExtra(AetherVpnService.EXTRA_DETAIL, detail)
            `package` = packageName
        })
    }

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
            val n = buildNotification(text)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Psiphon VPN", NotificationManager.IMPORTANCE_LOW)
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

    private fun stopTunnel() { stopTunnelInternal() }

    private fun stopTunnelInternal() {
        tunnel?.let { t ->
            try { t.stop() } catch (_: Throwable) {}
        }
        tunnel = null
        isConnected.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        if (hasFgService) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            hasFgService = false
        }
        try { stopSelf() } catch (_: Throwable) {}
    }

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.psiphon.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.psiphon.DISCONNECT"
        const val ACTION_GET_LOGS = "studio.cluvex.aethery.psiphon.GET_LOGS"
        const val ACTION_LOGS = "studio.cluvex.aethery.psiphon.LOGS"
        private const val CHANNEL_ID = "psiphon_vpn"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PsiphonVpn"
    }
}
