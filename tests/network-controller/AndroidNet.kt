package android.net

data class Network(val id: Int)
class NetworkCapabilities {
    companion object {
        const val NET_CAPABILITY_INTERNET = 12
        const val TRANSPORT_WIFI = 1
        const val TRANSPORT_CELLULAR = 0
    }
}
class NetworkRequest {
    class Builder {
        fun addCapability(value: Int) = this
        fun addTransportType(value: Int) = this
        fun setNetworkSpecifier(value: TelephonyNetworkSpecifier) = this
        fun build() = NetworkRequest()
    }
}
class TelephonyNetworkSpecifier {
    class Builder {
        fun setSubscriptionId(value: Int) = this
        fun build() = TelephonyNetworkSpecifier()
    }
}
class ConnectivityManager {
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
        open fun onLost(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {}
    }
    val callbacks = mutableListOf<NetworkCallback>()
    var failRegistration = false
    fun requestNetwork(request: NetworkRequest, callback: NetworkCallback) {
        if (failRegistration) throw SecurityException("test registration failure")
        callbacks.add(callback)
    }
    fun unregisterNetworkCallback(callback: NetworkCallback) {}
}
