package studio.cluvex.aethery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import ca.psiphon.PsiphonTunnel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service that hosts PsiphonTunnel.
 * Used when the user selects "Psiphon" as the connection protocol.
 * PsiphonTunnel internally manages VPN (via setVpnMode(true)).
 */
class PsiphonVpnService : Service(), PsiphonTunnel.HostService {

    private var tunnel: PsiphonTunnel? = null
    private val isConnected = AtomicBoolean(false)

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startFg("Starting Psiphon…")
                try {
                    tunnel = PsiphonTunnel.newPsiphonTunnel(this)
                    tunnel?.setVpnMode(true)
                    tunnel?.startTunneling("")   // config loaded via getPsiphonConfig()
                    Log.i(TAG, "PsiphonTunnel started")
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    broadcastStatus(STATUS_DISCONNECTED, "Psiphon error: ${e.message}")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tunnel?.stop()
        tunnel = null
        super.onDestroy()
    }

    private fun stopTunnel() {
        tunnel?.stop()
        tunnel = null
        isConnected.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        broadcastStatus(STATUS_DISCONNECTED, "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── PsiphonTunnel.HostService ─────────────────────────────

    override fun getContext(): Context = this

    override fun getPsiphonConfig(): String = """{
  "client_platform": "Android",
  "sponsor_id": "00000000-0000-0000-0000-000000000000",
  "propagation_channel_id": "00000000-0000-0000-0000-000000000000",
  "disable_replay": false,
  "remote_server_list_urls": ["https://proxy.psi.cash/server_list"],
  "tunnel_whole_device": true
}"""

    override fun loadLibrary(name: String) {
        System.loadLibrary(name)
    }

    override fun onDiagnosticMessage(message: String) {
        Log.d(TAG, message)
    }

    override fun onConnected() {
        isConnected.set(true)
        prefs().edit().putBoolean("vpn_connected", true).apply()
        startFg("Connected")
        broadcastStatus(STATUS_CONNECTED, "Psiphon tunnel established")
    }

    override fun onExiting() {
        isConnected.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        broadcastStatus(STATUS_DISCONNECTED, "Psiphon stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onClientRegion(region: String) {
        broadcastIntent(ACTION_REGION).putExtra("region", region).let(::sendBroadcast)
    }

    override fun onClientAddress(address: String) {
        broadcastIntent(ACTION_ADDRESS).putExtra("address", address).let(::sendBroadcast)
    }

    override fun onBytesTransferred(sent: Long, received: Long) {
        prefs().edit()
            .putLong("total_rx", received)
            .putLong("total_tx", sent)
            .putLong("live_rx", received)
            .putLong("live_tx", sent)
            .apply()
    }

    // ── overrides with default impl to avoid crashes ──────────
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}
    override fun onSocksProxyPortInUse(port: Int) {}
    override fun onHttpProxyPortInUse(port: Int) {}
    override fun onListeningSocksProxyPort(port: Int) {}
    override fun onListeningHttpProxyPort(port: Int) {}
    override fun onListeningSocksProxyUnixPath(path: String) {}
    override fun onListeningHttpProxyUnixPath(path: String) {}
    override fun onUpstreamProxyError(message: String) {}
    override fun onConnecting() {}
    override fun onHomepage(url: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onSplitTunnelRegions(regions: MutableList<String>) {}
    override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() {}
    override fun onStoppedWaitingForNetworkConnectivity() {}
    override fun onActiveAuthorizationIDs(authorizationIDs: MutableList<String>) {}
    override fun onTrafficRateLimits(upstreamBytesPerSecond: Long, downstreamBytesPerSecond: Long) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(message: String, reason: String, filteredRegions: MutableList<String>) {}
    override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(egressNodeCount: Int, proxyNodeCount: Int, brokerCount: Int, totalTransferredBytes: Long, totalConnectedSeconds: Long, icEgressSnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>, icProxySnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onConnectedServerRegion(region: String) {}
    override fun onLightProxyAvailable() {}
    override fun bindToDevice(fd: Long) {}

    // ── helpers ───────────────────────────────────────────────

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun startFg(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
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

    private fun broadcastIntent(action: String) = Intent(action).setPackage(packageName)

    private fun broadcastStatus(status: String, message: String) {
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply {
            putExtra(AetherVpnService.EXTRA_STATUS, status)
            putExtra(AetherVpnService.EXTRA_DETAIL, message)
            `package` = packageName
        })
    }

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.psiphon.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.psiphon.DISCONNECT"
        const val ACTION_STATUS = "studio.cluvex.aethery.psiphon.STATUS"
        const val ACTION_REGION = "studio.cluvex.aethery.psiphon.REGION"
        const val ACTION_ADDRESS = "studio.cluvex.aethery.psiphon.ADDRESS"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        private const val CHANNEL_ID = "psiphon_vpn"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PsiphonVpn"
    }
}
