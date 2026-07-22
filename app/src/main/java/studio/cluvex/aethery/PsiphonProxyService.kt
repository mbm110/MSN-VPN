package studio.cluvex.aethery

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import ca.psiphon.PsiphonTunnel
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Proxy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PsiphonProxyService — runs Psiphon as pure SOCKS5 proxy (NO VPN).
 * Then PsiphonTunService creates TUN with split tunneling and bridges
 * traffic to Psiphon's SOCKS5 port.
 */
class PsiphonProxyService : Service(), PsiphonTunnel.HostService {

    companion object {
        const val ACTION_START_PROXY = "studio.cluvex.aethery.psiphon.START_PROXY"
        const val ACTION_STOP_PROXY = "studio.cluvex.aethery.psiphon.STOP_PROXY"
        const val ACTION_START_TUN = "studio.cluvex.aethery.psiphon.START_TUN"
        const val ACTION_STOP_ALL = "studio.cluvex.aethery.psiphon.STOP_ALL"
        const val SOCKS_PORT = 10808
        const val EXTRA_STATUS = "status"
        const val STATUS_PROXY_READY = "PROXY_READY"
        private const val TAG = "PsiphonProxy"
    }

    private val bg = Executors.newSingleThreadExecutor { Thread(it, "PsiphonProxy").apply { isDaemon = true } }
    private var tunnel: PsiphonTunnel? = null
    private val isRunning = AtomicBoolean(false)
    private var hasFg = false

    override fun onCreate() { super.onCreate(); createCh() }
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROXY -> {
                ensureFg("Starting Psiphon proxy…")
                bg.submit { startProxy() }
            }
            ACTION_STOP_PROXY, ACTION_STOP_ALL -> bg.submit { stopProxy() }
        }
        return START_NOT_STICKY
    }

    private fun startProxy() {
        try {
            tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            tunnel?.setVpnMode(false)      // SOCKS5 ONLY — no TUN
            tunnel?.setClientPlatformAffixes("", "")
            val entries = try {
                assets.open("server_entries.txt").bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }
            tunnel?.startTunneling(entries)
            isRunning.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "proxy failed", e)
            stopProxy()
        } catch (t: Throwable) {
            Log.e(TAG, "proxy panic", t)
            stopProxy()
        }
    }

    private fun stopProxy() {
        try { tunnel?.stop() } catch (_: Throwable) {}
        tunnel = null
        isRunning.set(false)
        if (hasFg) try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { hasFg = false }
        try { stopSelf() } catch (_: Throwable) {}
    }

    // ── HostService ──
    override fun getContext(): Context = this
    override fun getPsiphonConfig(): String = buildConfig()
    override fun loadLibrary(name: String) { System.loadLibrary(name) }
    override fun bindToDevice(fd: Long) { /* SOCKS mode — no VPN protect needed */ }

    override fun onDiagnosticMessage(msg: String) {
        Log.i(TAG, msg)
        ConnectionLog.record("[Psiphon] $msg")
        // Detect proxy ready
        try {
            val n = JSONObject(msg)
            when (n.optString("noticeType", "")) {
                "ListeningSocksProxyPort" -> {
                    updateFg("Proxy ready :$SOCKS_PORT")
                    broadcastStatus(STATUS_PROXY_READY)
                }
                "Tunnels" -> if (n.optInt("count", 0) > 0) {
                    broadcastStatus(AetherVpnService.STATUS_CONNECTED)
                }
            }
        } catch (_: Exception) {}
    }
    override fun onConnecting() { broadcastStatus(AetherVpnService.STATUS_CONNECTING) }
    override fun onConnected() {
        isRunning.set(true)
        updateFg("Connected")
        broadcastStatus(AetherVpnService.STATUS_CONNECTED)
    }
    override fun onExiting() {
        isRunning.set(false)
        broadcastStatus(AetherVpnService.STATUS_DISCONNECTED)
        stopProxy()
    }
    override fun onClientRegion(r: String) {}
    override fun onClientAddress(a: String) {}
    override fun onConnectedServerRegion(r: String) {}
    override fun onBytesTransferred(s: Long, r: Long) {
        prefs().edit().putLong("total_rx", r).putLong("total_tx", s).putLong("live_rx", r).putLong("live_tx", s).apply()
    }
    override fun onAvailableEgressRegions(r: MutableList<String>) {}
    override fun onSocksProxyPortInUse(p: Int) {}
    override fun onHttpProxyPortInUse(p: Int) {}
    override fun onListeningSocksProxyPort(p: Int) {}
    override fun onListeningHttpProxyPort(p: Int) {}
    override fun onListeningSocksProxyUnixPath(p: String) {}
    override fun onListeningHttpProxyUnixPath(p: String) {}
    override fun onUpstreamProxyError(m: String) { ConnectionLog.record("[Psiphon] ⚠️ $m") }
    override fun onHomepage(u: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(f: String) {}
    override fun onSplitTunnelRegions(r: MutableList<String>) {}
    override fun onUntunneledAddress(a: String) {}
    override fun onStartedWaitingForNetworkConnectivity() { ConnectionLog.record("[Psiphon] ⏳ waiting for network") }
    override fun onStoppedWaitingForNetworkConnectivity() { ConnectionLog.record("[Psiphon] ✅ network restored") }
    override fun onActiveAuthorizationIDs(i: MutableList<String>) {}
    override fun onTrafficRateLimits(u: Long, d: Long) {}
    override fun onApplicationParameters(p: Any) {}
    override fun onServerAlert(m: String, r: String, rg: MutableList<String>) {}
    override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(e: Int, p: Int, b: Int, t: Long, s: Long,
        es: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>,
        ps: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}

    private fun buildConfig(): String {
        val c = JSONObject()
        c.put("SponsorId", "FFFFFFFFFFFFFFFF")
        c.put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        c.put("ClientPlatform", "Android")
        c.put("ClientVersion", "1")
        c.put("LocalSocksProxyPort", SOCKS_PORT)
        val dir = File(filesDir, "psiphon_data"); dir.mkdirs()
        c.put("DataRootDirectory", dir.absolutePath)
        c.put("EmitDiagnosticNotices", true)
        c.put("EmitBytesTransferred", true)
        c.put("EmitServerAlerts", true)
        c.put("DNSResolverAlternateServers", JSONArray().apply { put("1.1.1.1"); put("8.8.8.8") })
        c.put("EstablishTunnelTimeoutSeconds", 0)
        return c.toString()
    }

    private fun broadcastStatus(s: String) {
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply {
            putExtra(AetherVpnService.EXTRA_STATUS, s)
            `package` = packageName
        })
    }

    private fun ensureFg(t: String) {
        try {
            val n = Notification.Builder(this, "psiphon_proxy")
                .setContentTitle("Psiphon").setContentText(t)
                .setSmallIcon(R.drawable.ic_vpn_status_shield)
                .setOnlyAlertOnce(true).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(3, n)
            hasFg = true
        } catch (_: Throwable) {}
    }

    private fun updateFg(t: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(3, Notification.Builder(this, "psiphon_proxy")
                    .setContentTitle("Psiphon").setContentText(t)
                    .setSmallIcon(R.drawable.ic_vpn_status_shield)
                    .setOnlyAlertOnce(true).build())
        } catch (_: Exception) {}
    }

    private fun createCh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("psiphon_proxy", "Psiphon Proxy", NotificationManager.IMPORTANCE_LOW))
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)
}
