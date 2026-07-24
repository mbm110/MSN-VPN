package studio.cluvex.aethery

/**
 * Clean unified state management — single source of truth.
 * Written by AetherVpnService, read by MainActivity.
 * No more scattered lifecycle hacks.
 */
object VpnState {
    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    @Volatile var status: Status = Status.DISCONNECTED
    @Volatile var ip: String = ""
    @Volatile var countryCode: String = ""
    @Volatile var sessionStartMs: Long = 0
    @Volatile var lastError: String = ""

    fun setConnected(ip: String, countryCode: String) {
        this.status = Status.CONNECTED
        this.ip = ip
        this.countryCode = countryCode
        this.lastError = ""
    }

    fun setConnecting() {
        this.status = Status.CONNECTING
        this.lastError = ""
    }

    fun setFailed(error: String) {
        this.status = Status.FAILED
        this.lastError = error
    }

    fun setDisconnected() {
        this.status = Status.DISCONNECTED
        this.ip = ""
        this.countryCode = ""
    }

    fun isConnected() = status == Status.CONNECTED
    fun isConnecting() = status == Status.CONNECTING
}