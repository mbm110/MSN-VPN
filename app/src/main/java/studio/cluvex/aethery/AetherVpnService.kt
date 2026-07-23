package studio.cluvex.aethery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AetherVpnService : VpnService() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val readinessWorker = Executors.newSingleThreadScheduledExecutor()
    private val connected = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val userInitiatedDisconnect = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var readinessCheck: ScheduledFuture<*>? = null
    private var trafficCheck: ScheduledFuture<*>? = null
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTrafficSampleMs = 0L
    private var sessionRxBytes = 0L
    private var sessionTxBytes = 0L
    private var sessionStartMs = 0L
    private var reconnectScheduled = false
    private var reconnectAttempt = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                reconnectAttempt = 0
                reconnectScheduled = false
                sessionStartMs = SystemClock.elapsedRealtime()
                sessionRxBytes = 0
                sessionTxBytes = 0
                // Save session start time and config
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putLong("session_start", sessionStartMs)
                    .putString("saved_config", intent.getStringExtra(EXTRA_CONFIG))
                    .putBoolean("saved_vpn_mode", intent.getBooleanExtra(EXTRA_VPN_MODE, true))
                    .apply()
                startTunnel(intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY,
                    intent.getBooleanExtra(EXTRA_VPN_MODE, true))
            }
            ACTION_DISCONNECT -> {
                userInitiatedDisconnect.set(true)
                reconnectHandler.removeCallbacksAndMessages(null)
                reconnectAttempt = 0
                reconnectScheduled = false
                stopTunnel()
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        userInitiatedDisconnect.set(true)
        reconnectHandler.removeCallbacksAndMessages(null)
        stopTunnel(notify = false)
        worker.shutdownNow()
        readinessWorker.shutdownNow()
        super.onDestroy()
    }

    fun protectSocket(fd: Int): Boolean = protect(fd)

    private fun startTunnel(config: String, vpnMode: Boolean) {
        if (!connected.compareAndSet(false, true)) return
        stopRequested.set(false)
        reconnectScheduled = false
        startAsForeground()
        if (vpnMode) watchTraffic()
        sendStatus(STATUS_STARTING)
        worker.execute {
            try {
                val protocol = config.substringAfter("\"protocol\":\"").substringBefore('"').uppercase()
                ConnectionLog.record("Preparing $protocol identity")
                val result = if (vpnMode) {
                    val addresses = NativeCore.prepare(config)
                    ConnectionLog.record("Creating Android VPN interface")
                    tun = Builder()
                        .setSession("MSN-VPN")
                        .setMtu(1280)
                        .addAddress(addresses.ipv4, 32)
                        .addAddress(addresses.ipv6, 128)
                        .addRoute("0.0.0.0", 0)
                        .addRoute("::", 0)
                        .addDnsServer(dnsServer())
                        .applySplitTunneling()
                        .apply { if (!killSwitchEnabled()) allowBypass() }
                        .applyBypassIran()
                        .establish() ?: error("Android could not establish the VPN interface")
                    NativeCore.attach(this)
                    socketProtector = { fd -> protect(fd) }
                    ConnectionLog.record("Scanning gateways for VPN")
                    sendStatus(STATUS_SCANNING)
                    watchReadiness()
                    // Wait for upstream SOCKS proxy (Psiphon) before starting Rust core
                    waitForUpstreamProxy(config)
                    NativeCore.start(config, tun!!.fd)
                } else {
                    ConnectionLog.record("Starting local SOCKS5 proxy")
                    sendStatus(STATUS_SCANNING)
                    watchReadiness()
                    NativeCore.startProxy(config)
                }
                check(result == 0) { NativeCore.lastError() }
                check(stopRequested.get()) {
                    NativeCore.lastError().ifBlank { "Tunnel closed before setup completed" }
                }
                sendStatus(STATUS_DISCONNECTED)
            } catch (error: Exception) {
                val detail = NativeCore.lastError().ifBlank { error.message ?: "Tunnel setup failed" }
                Log.e(LOG_TAG, "Tunnel failed: $detail", error)
                sendStatus(STATUS_FAILED, detail)
            } finally {
                readinessCheck?.cancel(true)
                readinessCheck = null
                trafficCheck?.cancel(true)
                trafficCheck = null
                NativeCore.detach()
                socketProtector = null
                tun?.close()
                tun = null
                if (!reconnectScheduled) {
                    connected.set(false)
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putBoolean("vpn_connected", false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopTunnel(notify: Boolean = true) {
        stopRequested.set(true)
        readinessCheck?.cancel(true)
        readinessCheck = null
        trafficCheck?.cancel(true)
        trafficCheck = null
        NativeCore.stop()
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putLong("session_start", 0L).apply()
        saveSessionDataUsage()

        // Auto Reconnect: first priority when enabled and not user-initiated
        if (autoReconnectEnabled() && !userInitiatedDisconnect.get() && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempt++
            reconnectScheduled = true
            tun?.close()
            tun = null
            if (notify) sendStatus(STATUS_DISCONNECTED, "Reconnecting in 3s ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)")
            scheduleReconnect()
            return
        }

        // Kill Switch: block traffic when reconnect is off or exhausted
        if (killSwitchEnabled() && !userInitiatedDisconnect.get()) {
            activateKillSwitchBlackhole()
            if (notify) sendStatus(STATUS_DISCONNECTED)
            return
        }
        reconnectAttempt = 0
        reconnectScheduled = false

        tun?.close()
        tun = null
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("vpn_connected", false).apply()
        if (notify) sendStatus(STATUS_DISCONNECTED)
    }

    private fun waitForUpstreamProxy(config: String) {
        try {
            val json = org.json.JSONObject(config)
            val proxy = json.optString("upstream_proxy", "")
            if (proxy.isEmpty()) return // No upstream proxy needed
            val port = proxy.substringAfterLast(":").toIntOrNull() ?: return
            ConnectionLog.record("Waiting for upstream SOCKS proxy on :$port...")
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.Socket("127.0.0.1", port).use { 
                        ConnectionLog.record("Upstream SOCKS proxy ready on :$port")
                        return 
                    }
                } catch (_: Exception) {
                    Thread.sleep(500)
                }
            }
            ConnectionLog.record("Warning: upstream proxy not ready after 30s, proceeding anyway")
        } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        reconnectHandler.postDelayed({
            if (!stopRequested.get()) {
                val savedConfig = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("saved_config", null)
                if (savedConfig != null) {
                    connected.set(false)
                    userInitiatedDisconnect.set(false)
                    // Re-start the tunnel from the same process (don't use ACTION_CONNECT intent)
                    sessionStartMs = SystemClock.elapsedRealtime()
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putLong("session_start", sessionStartMs).apply()
                    startTunnel(savedConfig, getSharedPreferences("settings", MODE_PRIVATE)
                        .getBoolean("saved_vpn_mode", true))
                }
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun activateKillSwitchBlackhole() {
        try {
            tun?.close()
            tun = Builder()
                .setSession("MSN-VPN (Kill Switch)")
                .setMtu(1280)
                .addAddress("192.0.2.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("192.0.2.1")
                .allowBypass()
                .establish()
            ConnectionLog.record("Kill Switch: unexpected disconnect, blocking all traffic")
            sendStatus("KILL_SWITCH_BLOCKED")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Kill Switch blackhole failed: ${e.message}", e)
            tun?.close()
            tun = null
        }
    }

    private fun killSwitchEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("kill_switch", false)

    private fun autoReconnectEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_reconnect", true)

    private fun adBlockerEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("ad_blocker", false)

    private fun bypassIranEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("bypass_iran", false)

    private fun dnsServer(): String =
        if (adBlockerEnabled()) "94.140.14.14" else "1.1.1.1"

    private fun Builder.applyBypassIran(): Builder {
        if (!bypassIranEnabled()) return this
        IRANIAN_PACKAGES.forEach { pkg ->
            try { addDisallowedApplication(pkg) } catch (_: Exception) { }
        }
        ConnectionLog.record("Bypass Iran: ${IRANIAN_PACKAGES.size} app(s) bypass VPN")
        return this
    }

    private fun saveSessionDataUsage() {
        // Data already saved continuously in watchTraffic — no-op
    }

    private fun currentRxBytes(): Long = trafficBytes(TrafficStats.getUidRxBytes(applicationInfo.uid))
    private fun currentTxBytes(): Long = trafficBytes(TrafficStats.getUidTxBytes(applicationInfo.uid))

    private fun sendStatus(status: String, detail: String? = null) {
        Log.i(LOG_TAG, "status=$status${detail?.let { " detail=$it" } ?: ""}")
        ConnectionLog.record("${status.replaceFirstChar(Char::uppercase)}${detail?.let { ": $it" } ?: ""}")

        // Update vpn_connected flag for Quick Settings Tile
        if (status == STATUS_CONNECTED) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("vpn_connected", true).apply()
        } else if (status == STATUS_DISCONNECTED || status == STATUS_FAILED) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("vpn_connected", false).apply()
        }

        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .apply { detail?.let { putExtra(EXTRA_DETAIL, it) } }
            .apply { putExtra(EXTRA_ELAPSED, (SystemClock.elapsedRealtime() - sessionStartMs) / 1000) })
    }

    private fun watchReadiness() {
        readinessCheck?.cancel(true)
        readinessCheck = readinessWorker.scheduleAtFixedRate({
            if (NativeCore.isReady()) {
                ConnectionLog.record("CONNECT-IP accepted by gateway")
                sendStatus(STATUS_CONNECTED)
                readinessCheck?.cancel(false)
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ))
        val notification = notification(getString(R.string.vpn_notification))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun watchTraffic() {
        lastRxBytes = currentRxBytes()
        lastTxBytes = currentTxBytes()
        lastTrafficSampleMs = SystemClock.elapsedRealtime()
        trafficCheck?.cancel(true)
        trafficCheck = readinessWorker.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = (now - lastTrafficSampleMs).coerceAtLeast(1L)
            val rx = currentRxBytes()
            val tx = currentTxBytes()
            val rxDelta = (rx - lastRxBytes).coerceAtLeast(0L)
            val txDelta = (tx - lastTxBytes).coerceAtLeast(0L)
            val down = rxDelta * 1_000 / elapsedMs
            val up = txDelta * 1_000 / elapsedMs
            sessionRxBytes += rxDelta
            sessionTxBytes += txDelta
            lastRxBytes = rx
            lastTxBytes = tx
            lastTrafficSampleMs = now
            // Save live data usage for main screen + cumulative (crash-safe)
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val cumRx = prefs.getLong("total_rx", 0) + rxDelta
            val cumTx = prefs.getLong("total_tx", 0) + txDelta
            prefs.edit()
                .putLong("live_rx", cumRx)
                .putLong("live_tx", cumTx)
                .putLong("total_rx", cumRx)
                .putLong("total_tx", cumTx)
                .apply()
            val timer = formatDuration((SystemClock.elapsedRealtime() - sessionStartMs) / 1000)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("↓ ${formatRate(down)}  ↑ ${formatRate(up)}  $timer"))
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun notification(content: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AetherVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun trafficBytes(bytes: Long): Long =
        if (bytes == TrafficStats.UNSUPPORTED.toLong()) 0L else bytes

    private fun formatRate(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1_024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1_048_576 -> "${bytesPerSecond / 1_024} KB/s"
        else -> "${bytesPerSecond / 1_048_576} MB/s"
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    private fun formatData(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> "${bytes / 1_024} KB"
        bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
        else -> "%.1f GB".format(bytes.toDouble() / 1_073_741_824.0)
    }

    private fun Builder.applySplitTunneling(): Builder {
        val settings = SplitTunnelSettings(this@AetherVpnService)
        val packages = settings.packages()
        if (settings.mode() == SplitTunnelSettings.Mode.ALL) return this
        if (packages.isEmpty()) {
            check(settings.mode() != SplitTunnelSettings.Mode.INCLUDE) {
                "Select at least one app for split tunneling"
            }
            return this
        }
        packages.forEach { packageName ->
            try {
                when (settings.mode()) {
                    SplitTunnelSettings.Mode.INCLUDE -> addAllowedApplication(packageName)
                    SplitTunnelSettings.Mode.EXCLUDE -> addDisallowedApplication(packageName)
                    SplitTunnelSettings.Mode.ALL -> Unit
                }
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                ConnectionLog.record("Split tunnel skipped removed app: $packageName")
            }
        }
        ConnectionLog.record("Split tunnel ${settings.mode().label.lowercase()}: ${packages.size} app(s)")
        return this
    }

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.DISCONNECT"
        const val ACTION_STATUS = "studio.cluvex.aethery.STATUS"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_VPN_MODE = "vpn_mode"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_ELAPSED = "elapsed"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_STARTING = "starting"
        const val STATUS_SCANNING = "scanning"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_FAILED = "failed"
        const val STATUS_DISCONNECTED = "disconnected"
        private const val CHANNEL_ID = "aethery_vpn"
        private const val NOTIFICATION_ID = 1
        private const val LOG_TAG = "MSN-VPNVpn"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 3000L

        /** Called by PsiphonVpnService to protect sockets from AetherVpnService's TUN */
        @Volatile
        var socketProtector: ((Int) -> Boolean)? = null

        val IRANIAN_PACKAGES = listOf(
            "ir.divar",
            "ir.co.bazaar",
            "com.digikala",
            "com.snapp",
            "com.tapsi.ryde",
            "com.mydigipay.payment",
            "net.irankish.sb24",
            "com.sheypoor",
        )
    }
}

object ConnectionLog {
    private const val MAX_ENTRIES = 100
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun record(message: String) {
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $message")
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()
}
