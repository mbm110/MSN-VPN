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

/**
 * Clean VPN architecture:
 * - Psiphon is ALWAYS excluded from the TUN (addDisallowedApplication).
 *   Psiphon runs as a background Service (not VpnService) and connects
 *   directly to the Internet. Its SOCKS proxy is then used as upstream
 *   for device traffic going through the TUN.
 * - socketProtector is set BEFORE establish() so all subsequent sockets
 *   (Rust core's outbound) are properly protected.
 * - Unified state via VpnState object (no more scattered broadcasts).
 * - Psiphon + AetherVpnService start simultaneously; Rust core waits for
 *   the SOCKS port to become available.
 */
class AetherVpnService : VpnService() {

    // ── Thread pools ──────────────────────────────────────────────
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    // ── State ─────────────────────────────────────────────────────
    private val connected = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val userDisconnect = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var trafficJob: ScheduledFuture<*>? = null
    private var sessionRx = 0L
    private var sessionTx = 0L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                reconnectAttempt = 0
                sessionRx = 0
                sessionTx = 0
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
                val vpnMode = intent.getBooleanExtra(EXTRA_VPN_MODE, true)
                val savedMode = getSharedPreferences("settings", MODE_PRIVATE)
                savedMode.edit()
                    .putString("saved_config", config)
                    .putBoolean("saved_vpn_mode", vpnMode)
                    .putLong("session_start", SystemClock.elapsedRealtime())
                    .apply()
                startTunnel(config, vpnMode)
            }
            ACTION_DISCONNECT -> {
                userDisconnect.set(true)
                reconnectHandler.removeCallbacksAndMessages(null)
                stopTunnel()
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        userDisconnect.set(true)
        reconnectHandler.removeCallbacksAndMessages(null)
        stopTunnel(notify = false)
        worker.shutdownNow()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    // Called by the Rust JNI layer to protect sockets
    fun protectSocket(fd: Int): Boolean = protect(fd)

    // ── Core tunnel logic ───────────────────────────────────────────

    private fun startTunnel(config: String, vpnMode: Boolean) {
        if (!connected.compareAndSet(false, true)) return
        stopRequested.set(false)
        VpnState.setConnecting()

        startForegroundCompat()
        if (vpnMode) startTrafficWatch()

        worker.execute {
            try {
                val protocol = parseProtocol(config)
                ConnectionLog.record("Preparing $protocol identity")

                if (!vpnMode) {
                    ConnectionLog.record("Starting SOCKS proxy mode")
                    NativeCore.attach(this)
                    socketProtector = { fd -> protect(fd) }
                    NativeCore.startProxy(config)
                    watchReady()
                    return@execute
                }

                // VPN mode: build TUN with Psiphon EXCLUDED
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
                    // CRITICAL: exclude this app so Psiphon bypasses the TUN
                    // and reaches the Internet directly (no routing loop)
                    .also { builder ->
                        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                    }
                    .applySplitTunneling()
                    .apply { if (!killSwitchEnabled()) allowBypass() }
                    .applyBypassIran()
                    .establish() ?: error("Android could not establish the VPN interface")

                // Set socketProtector BEFORE NativeCore.start() so ALL Rust sockets
                // (including any DNS/control connections) are protected from the TUN
                NativeCore.attach(this)
                socketProtector = { fd -> protect(fd) }

                ConnectionLog.record("Scanning gateways for VPN")
                watchReady()

                // Wait for upstream SOCKS proxy (Psiphon) before starting Rust core
                waitForUpstreamProxy(config)

                NativeCore.start(config, tun!!.fd)

                check(NativeCore.isReady()) { NativeCore.lastError() }
                check(!stopRequested.get()) { "Tunnel stopped before setup completed" }

                VpnState.setDisconnected()
            } catch (error: Exception) {
                val detail = NativeCore.lastError().ifBlank { error.message ?: "Tunnel setup failed" }
                Log.e(LOG_TAG, "Tunnel failed: $detail", error)
                ConnectionLog.record("FAILED: $detail")
                VpnState.setFailed(detail)
                broadcastStatus(VpnState.status, detail)
            } finally {
                trafficJob?.cancel(true)
                trafficJob = null
                NativeCore.detach()
                socketProtector = null

                if (!handleReconnect()) {
                    tun?.close()
                    tun = null
                    connected.set(false)
                    VpnState.setDisconnected()
                    broadcastStatus(VpnState.status)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopTunnel(notify: Boolean = true) {
        stopRequested.set(true)
        trafficJob?.cancel(true)
        trafficJob = null
        NativeCore.stop()
        VpnState.setDisconnected()
        if (notify) broadcastStatus(VpnState.status)

        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("vpn_connected", false)
            .putLong("session_start", 0)
            .apply()

        if (!handleReconnect()) {
            tun?.close()
            tun = null
            connected.set(false)
            reconnectAttempt = 0
        }
    }

    /** Returns true if reconnect was scheduled, false if done */
    private fun handleReconnect(): Boolean {
        if (userDisconnect.get()) return false
        if (killSwitchEnabled() && reconnectAttempt >= MAX_RECONNECT) {
            activateKillSwitch()
            return true
        }
        if (reconnectAttempt < MAX_RECONNECT) {
            reconnectAttempt++
            reconnectHandler.postDelayed({
                if (!stopRequested.get()) {
                    reconnectAttempt = 0
                    worker.execute {
                        connected.set(false)
                        userDisconnect.set(false)
                        val savedConfig = getSharedPreferences("settings", MODE_PRIVATE)
                            .getString("saved_config", null) ?: return@execute
                        startTunnel(savedConfig,
                            getSharedPreferences("settings", MODE_PRIVATE)
                                .getBoolean("saved_vpn_mode", true))
                    }
                }
            }, RECONNECT_DELAY_MS)
            return true
        }
        return false
    }

    private fun activateKillSwitch() {
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
            ConnectionLog.record("Kill Switch: blocking all traffic")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Kill Switch failed: ${e.message}", e)
            tun?.close()
            tun = null
        }
    }

    // ── Wait for Psiphon SOCKS port ────────────────────────────────

    private fun waitForUpstreamProxy(config: String) {
        val proxy = runCatching {
            org.json.JSONObject(config).optString("upstream_proxy", "")
        }.getOrNull() ?: return
        if (proxy.isEmpty()) return

        val port = proxy.substringAfterLast(":").toIntOrNull() ?: return
        ConnectionLog.record("Waiting for Psiphon SOCKS on :$port...")

        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested.get()) return
            try {
                java.net.Socket("127.0.0.1", port).use {
                    ConnectionLog.record("Psiphon SOCKS ready on :$port")
                    return
                }
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
        ConnectionLog.record("Warning: Psiphon SOCKS not ready after 30s, proceeding anyway")
    }

    // ── Ready watcher ─────────────────────────────────────────────

    private fun watchReady() {
        scheduler.execute {
            while (!stopRequested.get() && !NativeCore.isReady()) {
                Thread.sleep(250)
            }
            if (stopRequested.get()) return@execute

            ConnectionLog.record("Tunnel ready")
            VpnState.setConnected("", "")
            broadcastStatus(VpnState.status)
            fetchIpAsync()
        }
    }

    private fun fetchIpAsync() {
        scheduler.execute {
            val ipFetcher = IpFetcher()
            val result = ipFetcher.fetch()
            VpnState.ip = result.ip
            VpnState.countryCode = result.countryCode
            broadcastIpResult(result.ip, result.countryCode)
        }
    }

    // ── Traffic monitoring ─────────────────────────────────────────

    private fun startTrafficWatch() {
        trafficJob = scheduler.scheduleAtFixedRate({
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val startMs = prefs.getLong("session_start", 0)
            if (startMs == 0L) return@scheduleAtFixedRate

            val rx = currentRxBytes()
            val tx = currentTxBytes()
            sessionRx += rx
            sessionTx += tx
            val timer = formatDuration((SystemClock.elapsedRealtime() - startMs) / 1000)

            val liveRx = prefs.getLong("total_rx", 0) + rx
            val liveTx = prefs.getLong("total_tx", 0) + tx
            prefs.edit()
                .putLong("live_rx", liveRx)
                .putLong("live_tx", liveTx)
                .putLong("total_rx", liveRx)
                .putLong("total_tx", liveTx)
                .apply()

            val content = "↓ ${formatRate(rx)}  ↑ ${formatRate(tx)}  $timer"
            runCatching {
                (getSystemService(NotificationManager::class.java) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification(content))
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    // ── Broadcast helpers ──────────────────────────────────────────

    private fun broadcastStatus(status: VpnState.Status, detail: String = "") {
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, when (status) {
                VpnState.Status.CONNECTING -> STATUS_CONNECTING
                VpnState.Status.CONNECTED -> STATUS_CONNECTED
                VpnState.Status.FAILED -> STATUS_FAILED
                VpnState.Status.DISCONNECTED -> STATUS_DISCONNECTED
            })
            .putExtra(EXTRA_DETAIL, detail)
            .apply {
                if (status == VpnState.Status.CONNECTED) {
                    val startMs = getSharedPreferences("settings", MODE_PRIVATE)
                        .getLong("session_start", 0)
                    putExtra(EXTRA_ELAPSED, (SystemClock.elapsedRealtime() - startMs) / 1000)
                }
            })
    }

    private fun broadcastIpResult(ip: String, countryCode: String) {
        sendBroadcast(Intent(ACTION_IP_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_IP, ip)
            .putExtra(EXTRA_COUNTRY, countryCode))
    }

    // ── Foreground notification ────────────────────────────────────

    private fun startForegroundCompat() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NotificationManager::class.java) as NotificationManager)
            .createNotificationChannel(channel)

        val n = notification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notification(content: String): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, AetherVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun parseProtocol(config: String) =
        config.substringAfter("\"protocol\":\"").substringBefore('"').uppercase()

    private fun currentRxBytes(): Long = trafficBytes(TrafficStats.getUidRxBytes(applicationInfo.uid))
    private fun currentTxBytes(): Long = trafficBytes(TrafficStats.getUidTxBytes(applicationInfo.uid))
    private fun trafficBytes(bytes: Long): Long = if (bytes == TrafficStats.UNSUPPORTED.toLong()) 0L else bytes

    private fun killSwitchEnabled() = getSharedPreferences("settings", MODE_PRIVATE)
        .getBoolean("kill_switch", false)
    private fun dnsServer() = if (getSharedPreferences("settings", MODE_PRIVATE)
        .getBoolean("ad_blocker", false)) "94.140.14.14" else "1.1.1.1"

    private fun Builder.applyBypassIran(): Builder {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("bypass_iran", false)) return this
        IRANIAN_PACKAGES.forEach { try { addDisallowedApplication(it) } catch (_: Exception) {} }
        return this
    }

    private fun Builder.applySplitTunneling(): Builder {
        val settings = SplitTunnelSettings(this@AetherVpnService)
        val packages = settings.packages()
        if (settings.mode() == SplitTunnelSettings.Mode.ALL) return this
        if (packages.isEmpty()) {
            check(settings.mode() != SplitTunnelSettings.Mode.INCLUDE) { "Select at least one app" }
            return this
        }
        packages.forEach { pkg ->
            try {
                when (settings.mode()) {
                    SplitTunnelSettings.Mode.INCLUDE -> addAllowedApplication(pkg)
                    SplitTunnelSettings.Mode.EXCLUDE -> addDisallowedApplication(pkg)
                    SplitTunnelSettings.Mode.ALL -> {}
                }
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {}
        }
        return this
    }

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

    // ── Companion ──────────────────────────────────────────────────

    companion object {
        const val ACTION_CONNECT = "studio.cluvex.aethery.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aethery.DISCONNECT"
        const val ACTION_STATUS = "studio.cluvex.aethery.STATUS"
        const val ACTION_IP_RESULT = "studio.cluvex.aethery.IP_RESULT"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_VPN_MODE = "vpn_mode"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_IP = "ip"
        const val EXTRA_COUNTRY = "country"
        const val EXTRA_ELAPSED = "elapsed"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_FAILED = "failed"
        const val STATUS_DISCONNECTED = "disconnected"
        private const val CHANNEL_ID = "aethery_vpn"
        private const val NOTIFICATION_ID = 1
        private const val LOG_TAG = "AetherVpn"
        private const val MAX_RECONNECT = 3
        private const val RECONNECT_DELAY_MS = 3000L

        /**
         * CRITICAL: Set by AetherVpnService BEFORE NativeCore.start().
         * Used by PsiphonVpnService to protect its sockets from the TUN.
         */
        @Volatile var socketProtector: ((Int) -> Boolean)? = null

        val IRANIAN_PACKAGES = listOf(
            "ir.divar", "ir.co.bazaar", "com.digikala", "com.snapp",
            "com.tapsi.ryde", "com.mydigipay.payment",
            "net.irankish.sb24", "com.sheypoor",
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