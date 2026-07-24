package studio.cluvex.aethery

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Standalone IP fetcher — completely independent of Psiphon's state.
 * Fetches IP + country code through any available network path.
 * Must NOT go through the TUN (that's the Psiphon proxy's job for VPN mode).
 * For Psiphon VPN: uses PsiphonVpnService's broadcast.
 * For WireGuard/Masque: uses direct HTTP through the TUN (which IS the tunnel).
 */
object IpFetcher {

    data class IpResult(val ip: String, val countryCode: String)

    fun fetch(ipFetcherListener: IpFetcherListener? = null): IpResult {
        val ip = fetchIp()
        val country = if (ip.isNotEmpty()) fetchCountry(ip) else ""
        ipFetcherListener?.onIpResult(ip, country)
        return IpResult(ip, country)
    }

    /**
     * Fetch public IP via ipify (returns raw JSON like {"ip":"1.2.3.4"}).
     * Uses direct HTTP — the OS routing table determines the path:
     * - WireGuard/Masque: direct through TUN (correct — this IS the tunnel)
     * - Psiphon standalone: direct (correct — no TUN active)
     */
    private fun fetchIp(): String {
        return httpGet("https://api.ipify.org?format=json")?.let { json ->
            try {
                JSONObject(json).optString("ip", "").ifEmpty { null }
            } catch (_: Exception) { null }
        } ?: ""
    }

    /**
     * Fetch country code via ip-api.com.
     * Uses HTTP (not HTTPS) to avoid TLS negotiation quirks.
     */
    private fun fetchCountry(ip: String): String {
        val json = httpGet("http://ip-api.com/json/$ip?fields=countryCode") ?: return ""
        return try {
            val obj = JSONObject(json)
            obj.optString("countryCode", "").ifEmpty { null } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.connect()
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    interface IpFetcherListener {
        fun onIpResult(ip: String, countryCode: String)
    }
}