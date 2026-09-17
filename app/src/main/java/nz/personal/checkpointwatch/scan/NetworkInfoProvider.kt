package nz.personal.checkpointwatch.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * The one thing the app needs to know about the phone's connection: whether it is going through a
 * VPN.
 *
 * It is asked for one reason only — a scan Facebook rationed reads very differently depending on
 * the answer. On a VPN it is expected, permanent, and worth explaining once; without one it is a
 * quirk of that particular scan. Nothing else in the app branches on this, nothing is recorded but
 * the flag, and it never decides whether a scan runs.
 *
 * An interface so the coordinator stays testable on the JVM, where there is no ConnectivityManager.
 */
fun interface NetworkInfoProvider {

    /** True when the phone's current network runs over a VPN. Never throws. */
    fun isVpnActive(): Boolean
}

/**
 * The real one. `ACCESS_NETWORK_STATE` is already in the manifest — WorkManager merged it in long
 * before this, and it is now declared explicitly there so the permission table can say why the app
 * holds it.
 *
 * Every failure is "we don't know", which reads as "not on a VPN": the flag only ever adds a
 * sentence to a banner, and a missing sentence is a far smaller cost than a scan that threw.
 */
class AndroidNetworkInfoProvider(context: Context) : NetworkInfoProvider {

    private val appContext = context.applicationContext

    override fun isVpnActive(): Boolean = try {
        val manager = appContext.getSystemService<ConnectivityManager>()
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }
}
