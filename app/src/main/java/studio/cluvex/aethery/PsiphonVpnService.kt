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
import java.util.concurrent.atomic.AtomicBoolean

class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    private var tunnel: PsiphonTunnel? = null
    private val isConnected = AtomicBoolean(false)
    private var hasFgService = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "ACTION_CONNECT received")
                // Start foreground FIRST (before any heavy work)
                try {
                    startFg("Starting Psiphon…")
                    hasFgService = true
                    Log.i(TAG, "Foreground started")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to start foreground", e)
                }
                // Now initialize tunnel – catch EVERYTHING
                try {
                    Log.i(TAG, "Creating PsiphonTunnel…")
                    tunnel = PsiphonTunnel.newPsiphonTunnel(this)
                    Log.i(TAG, "Tunnel instance created")
                    tunnel?.setVpnMode(true)
                    Log.i(TAG, "VPN mode set, starting tunneling…")
                    tunnel?.startTunneling("")
                    // NOTE: startTunneling returns immediately if Go starts OK
                    // If it throws we'll catch it below
                    Log.i(TAG, "startTunneling returned successfully")
                    sendStatus(AetherVpnService.STATUS_STARTING)
                } catch (e: Throwable) {
                    Log.e(TAG, "FAILED to start Psiphon", e)
                    sendStatus(AetherVpnService.STATUS_DISCONNECTED, e.toString())
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "ACTION_DISCONNECT received")
                stopTunnel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        try { tunnel?.stop() } catch (_: Throwable) {}
        tunnel = null
        if (hasFgService) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        }
        super.onDestroy()
    }

    private fun stopTunnel() {
        try { tunnel?.stop() } catch (_: Throwable) {}
        tunnel = null
        isConnected.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        if (hasFgService) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            hasFgService = false
        }
        stopSelf()
    }

    // ==================== HostService impl ====================

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
        Log.i(TAG, "Loading native library: $name")
        System.loadLibrary(name)
        Log.i(TAG, "Library loaded: $name")
    }

    override fun onDiagnosticMessage(message: String) {
        Log.i(TAG, "[Psiphon] $message")
    }

    override fun onConnecting() {
        Log.i(TAG, "onConnecting")
        sendStatus(AetherVpnService.STATUS_CONNECTING)
    }

    override fun onConnected() {
        Log.i(TAG, "onConnected")
        isConnected.set(true)
        prefs().edit().putBoolean("vpn_connected", true).apply()
        sendStatus(AetherVpnService.STATUS_CONNECTED)
    }

    override fun onExiting() {
        Log.i(TAG, "onExiting")
        isConnected.set(false)
        prefs().edit().putBoolean("vpn_connected", false).apply()
        sendStatus(AetherVpnService.STATUS_DISCONNECTED)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
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

    // Stubs for remaining HostService methods
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}
    override fun onSocksProxyPortInUse(port: Int) {}
    override fun onHttpProxyPortInUse(port: Int) {}
    override fun onListeningSocksProxyPort(port: Int) {}
    override fun onListeningHttpProxyPort(port: Int) {}
    override fun onListeningSocksProxyUnixPath(path: String) {}
    override fun onListeningHttpProxyUnixPath(path: String) {}
    override fun onUpstreamProxyError(message: String) {
        Log.e(TAG, "Upstream proxy error: $message")
    }
    override fun onHomepage(url: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onSplitTunnelRegions(regions: MutableList<String>) {}
    override fun onUntunneledAddress(address: String) {}
    override fun onStartedWaitingForNetworkConnectivity() {
        Log.w(TAG, "Waiting for network connectivity…")
    }
    override fun onStoppedWaitingForNetworkConnectivity() {}
    override fun onActiveAuthorizationIDs(authorizationIDs: MutableList<String>) {}
    override fun onTrafficRateLimits(upstreamBytesPerSecond: Long, downstreamBytesPerSecond: Long) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(message: String, reason: String, filteredRegions: MutableList<String>) {}
    override fun onInproxyMustUpgrade() {}
    override fun onInproxyProxyActivity(egressNodeCount: Int, proxyNodeCount: Int, brokerCount: Int, totalTransferredBytes: Long, totalConnectedSeconds: Long, icEgressSnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>, icProxySnapshot: MutableMap<String, PsiphonTunnel.RegionActivitySnapshot>) {}
    override fun onLightProxyAvailable() {}
    override fun bindToDevice(fd: Long) {}

    // ==================== helpers ====================

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun sendStatus(status: String, detail: String? = null) {
        sendBroadcast(Intent(AetherVpnService.ACTION_STATUS).apply {
            putExtra(AetherVpnService.EXTRA_STATUS, status)
            if (detail != null) putExtra(AetherVpnService.EXTRA_DETAIL, detail)
            `package` = packageName
        })
    }

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

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.psiphon.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.psiphon.DISCONNECT"
        private const val CHANNEL_ID = "psiphon_vpn"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PsiphonVpn"
    }
}
