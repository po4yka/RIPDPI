package com.poyka.ripdpi.services

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.poyka.ripdpi.core.RipDpiSshHostKeyBindings
import com.poyka.ripdpi.core.RipDpiSshHostKeyProbe
import com.poyka.ripdpi.core.SshProbeSocketController
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowVpnService
import java.net.InetAddress

internal class FakeSshHostKeyProbeBindings : RipDpiSshHostKeyBindings {
    val fingerprint = "SHA256:" + "A".repeat(43)
    var calls = 0
    var address: String? = null

    override fun probeHostKey(
        addressLiteral: String,
        port: Int,
        timeoutMillis: Int,
        socketController: SshProbeSocketController,
        observationOut: Array<String?>,
    ): Int {
        calls += 1
        address = addressLiteral
        observationOut[0] = fingerprint
        observationOut[1] = "ssh-ed25519"
        return 0
    }
}

/** JVM has no Android InetAddress.getAllByNameOnNet; production always uses Network.getAllByName. */
internal class FakeSshProbeAddressResolver : (Network, String) -> Array<InetAddress> {
    var calls = 0
    var beforeReturn: () -> Unit = {}

    override fun invoke(
        network: Network,
        host: String,
    ): Array<InetAddress> {
        calls += 1
        beforeReturn()
        return arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    }
}

internal fun sshProbeTestCapabilities(): NetworkCapabilities =
    NetworkCapabilities().also { capabilities ->
        listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
        ).forEach { capability ->
            NetworkCapabilities::class.java
                .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                .invoke(capabilities, capability)
        }
    }

internal class SshObserverFixture(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val context = RuntimeEnvironment.getApplication()
    private val serviceController = Robolectric.buildService(SshHostKeyProbeService::class.java).create()
    val application = shadowOf(context)
    val bindings = FakeSshHostKeyProbeBindings()
    val resolver = FakeSshProbeAddressResolver()
    val observer =
        DefaultSshHostKeyObserver(
            context,
            RipDpiSshHostKeyProbe(bindings),
            scope,
            AppCoroutineDispatchers(dispatcher, dispatcher, dispatcher),
            resolver,
        )
    val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    init {
        ShadowVpnService.setPrepareResult(null)
        application.grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        application.setBindServiceCallsOnServiceConnectedDirectly(true)
        application.setComponentNameAndServiceForBindService(
            ComponentName(context, SshHostKeyProbeService::class.java),
            serviceController.get().onBind(
                Intent(context, SshHostKeyProbeService::class.java).setAction(SshHostKeyProbeService.BindAction),
            ),
        )
    }

    fun publishUnderlay(): Network {
        val callback = connectivity.networkCallbacks.single()
        val network = ShadowNetwork.newInstance(707)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, sshProbeTestCapabilities())
        callback.onLinkPropertiesChanged(
            network,
            LinkProperties().apply { setDnsServers(listOf(InetAddress.getByName("192.0.2.53"))) },
        )
        return network
    }

    override fun close() {
        serviceController.destroy()
    }
}
