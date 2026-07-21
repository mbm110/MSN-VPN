package studio.cluvex.aethery

import android.graphics.drawable.Icon
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class VpnTileService : TileService() {

    override fun onStartListening() {
        updateTileState()
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { toggleVpn() }
        } else {
            toggleVpn()
        }
    }

    private fun toggleVpn() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val connected = prefs.getBoolean("vpn_connected", false)

        if (connected) {
            // Disconnect
            startService(Intent(this, AetherVpnService::class.java)
                .setAction(AetherVpnService.ACTION_DISCONNECT))
        } else {
            // Connect with saved config
            val savedConfig = prefs.getString("saved_config", null)
            val vpnMode = prefs.getBoolean("saved_vpn_mode", true)
            if (savedConfig != null) {
                if (VpnService.prepare(this) != null) {
                    // VPN permission needed — open the app
                    startActivityAndCollapse(Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
                startService(Intent(this, AetherVpnService::class.java)
                    .setAction(AetherVpnService.ACTION_CONNECT)
                    .putExtra(AetherVpnService.EXTRA_CONFIG, savedConfig)
                    .putExtra(AetherVpnService.EXTRA_VPN_MODE, vpnMode))
            } else {
                startActivityAndCollapse(Intent(this, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun updateTileState() {
        val connected = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("vpn_connected", false)
        qsTile?.let { tile ->
            tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_status_shield)
            tile.label = if (connected) "Disconnect" else "Connect"
            tile.updateTile()
        }
    }
}
