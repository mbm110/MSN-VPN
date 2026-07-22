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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground VpnService hosting Psiphon tunnel.
 * Architecture follows MahsaNG's proven approach:
 *   1. Start foreground notification immediately
 *   2. Establish VPN TUN interface on background thread
 *   3. Start Psiphon tunnel in VPN mode
 *   4. bindToDevice() calls protect() to prevent routing loops
 */
class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    private var tunnel: PsiphonTunnel? = null
    private val isConnected = AtomicBoolean(false)
    private var hasFgService = false

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // 1. Foreground FIRST (Android 14+ requires instant startForeground)
                ensureFg("Starting Psiphon…")

                // 2. Start tunnel on background thread to avoid blocking main thread
                Thread { startTunnel() }.apply {
                    name = "PsiphonInit"
                    start()
                }
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Disconnect requested")
                stopTunnel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopTunnelInternal()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopTunnel()
    }

    private fun startTunnel() {
        try {
            // Establish VPN TUN before Psiphon (prevents socket routing issues)
            Log.i(TAG, "Establishing VPN TUN interface…")
            establishVpnInterface()

            Log.i(TAG, "Creating PsiphonTunnel instance…")
            tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            tunnel?.setClientPlatformAffixes("", "")
            tunnel?.setVpnMode(true)

            Log.i(TAG, "Starting tunnel…")
            tunnel?.startTunneling("") // server entries loaded from assets if available
            Log.i(TAG, "startTunneling returned — Psiphon is running in background")
            sendStatus(AetherVpnService.STATUS_CONNECTING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Psiphon: ${e.message}", e)
            sendStatus(AetherVpnService.STATUS_DISCONNECTED, "Psiphon error: ${e.message}")
            stopTunnelInternal()
        }
    }

    // ── VPN TUN interface (before Psiphon) ─────────────────────

    private fun establishVpnInterface() {
        val builder = Builder()
        builder.setMtu(1500)
        builder.addAddress("10.0.0.1", 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")
        builder.setSession("MSN-VPN Psiphon")

        val iface = builder.establish()
            ?: throw Exception("VpnService.Builder.establish() returned null — VPN permission not granted")
        iface.close() // We keep the VPN established; Psiphon uses its own TUN internally
        isConnected.set(true)
        Log.i(TAG, "VPN TUN interface established successfully")
    }

    // ── PsiphonTunnel.HostService ─────────────────────────────

    override fun getContext(): Context = this

    override fun getPsiphonConfig(): String = buildConfig()

    override fun loadLibrary(name: String) {
        Log.i(TAG, "Loading native library: $name")
        System.loadLibrary(name)
        Log.i(TAG, "Native library loaded: $name")
    }

    /**
     * CRITICAL: Psiphon's Go runtime calls this for every socket it opens.
     * Must call VpnService.protect() to exempt Psiphon's connections from
     * the VPN routing loop. WITHOUT THIS the Go runtime panics immediately.
     */
    override fun bindToDevice(fd: Long) {
        val result = protect(fd.toInt())
        if (!result) {
            throw RuntimeException("VpnService.protect() failed for fd=$fd")
        }
        Log.v(TAG, "protect(fd=$fd) OK")
    }

    override fun onDiagnosticMessage(message: String) {
        Log.d(TAG, "[Psiphon] $message")
    }

    override fun onConnecting() {
        Log.i(TAG, "onConnecting")
        sendStatus(AetherVpnService.STATUS_CONNECTING)
    }

    override fun onConnected() {
        Log.i(TAG, "onConnected")
        isConnected.set(true)
        prefs().edit().putBoolean("vpn_connected", true).apply()
        updateFg("Psiphon Connected")
        sendStatus(AetherVpnService.STATUS_CONNECTED)
    }

    override fun onExiting() {
        Log.i(TAG, "onExiting")
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
    override fun onUpstreamProxyError(message: String) { Log.e(TAG, "Upstream proxy: $message") }
    override fun onHomepage(url: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onSplitTunnelRegions(regions: MutableList<String>) {}
    override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() { Log.w(TAG, "Waiting for network…") }
    override fun onStoppedWaitingForNetworkConnectivity() { Log.i(TAG, "Network OK") }
    override fun onActiveAuthorizationIDs(ids: MutableList<String>) {}
    override fun onTrafficRateLimits(up: Long, down: Long) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(msg: String, reason: String, regions: MutableList<String>) {}
    override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(egressNodeCount: Int, proxyNodeCount: Int, brokerCount: Int, totalTransferredBytes: Long, totalConnectedSeconds: Long, icEgressSnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>, icProxySnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}

    // ── Config builder (matches MahsaNG's proven format) ───────

    private fun buildConfig(): String {
        // Load base config from assets/psiphon_config.json if exists
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

        // Data directory
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
            put("1.1.1.1")
            put("1.0.0.1")
            put("8.8.8.8")
            put("8.8.4.4")
        })

        // No timeout
        config.put("EstablishTunnelTimeoutSeconds", 0)

        return config.toString()
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
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed", e)
        }
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
            .setContentTitle("MSN-VPN Psiphon")
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun stopTunnel() {
        stopTunnelInternal()
    }

    private fun stopTunnelInternal() {
        try { tunnel?.stop() } catch (_: Throwable) {}
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
        private const val CHANNEL_ID = "psiphon_vpn"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PsiphonVpn"
    }
}
