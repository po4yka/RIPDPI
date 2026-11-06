package com.poyka.ripdpi.data.diagnostics

import android.net.Network
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.BundledDnsProviderCatalog
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.NumericIp
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.FileDescriptor
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

enum class DetectionResolverMode {
    SYSTEM,
    DIRECT,
    DOH,
}

enum class DetectionAddressFamily {
    ANY,
    IPV4,
    IPV6,
}

enum class DnsRecordType(
    val wireCode: Int,
) {
    A(1),
    AAAA(28),
}

data class DnsPreset(
    val id: String,
    val servers: List<String>,
    val dohUrl: String,
    val dohHost: String,
) {
    companion object {
        /**
         * Every DoH-capable bundled catalog entry, mapped onto the diagnostics-facing [DnsPreset]
         * shape and cached so each id yields one stable instance (the [assertSame] identity the
         * resolver tests rely on). Sourced from [BundledDnsProviderCatalog] — the same offline
         * catalog the encrypted-DNS settings pipeline and the `:core:service` loader agree on.
         */
        val ALL: List<DnsPreset> =
            BundledDnsProviderCatalog.providers
                .filter { it.supportsDoh && it.dohUrl.isNotBlank() }
                .map { entry ->
                    DnsPreset(
                        id = entry.id,
                        servers = entry.bootstrapIps,
                        dohUrl = entry.dohUrl,
                        dohHost = entry.tlsServerName.ifBlank { entry.dotHost },
                    )
                }

        /** Convenience accessors for the historically named presets; resolve via the catalog by id. */
        val CLOUDFLARE: DnsPreset = requireNotNull(byId("cloudflare")) { "cloudflare missing from DNS catalog" }
        val GOOGLE: DnsPreset = requireNotNull(byId("google")) { "google missing from DNS catalog" }
        val YANDEX: DnsPreset = requireNotNull(byId("yandex")) { "yandex missing from DNS catalog" }

        private fun byId(id: String): DnsPreset? = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }

        fun byName(name: String): DnsPreset? =
            ALL.firstOrNull { it.id.equals(name, ignoreCase = true) || it.dohHost.equals(name, ignoreCase = true) }
    }
}

sealed interface DetectionNetworkBinding {
    data object None : DetectionNetworkBinding

    data class AndroidNetworkBinding(
        val network: Network,
    ) : DetectionNetworkBinding

    data class OsDeviceBinding(
        val interfaceName: String,
    ) : DetectionNetworkBinding {
        init {
            require(interfaceName.isNotBlank()) { "interfaceName must not be blank" }
        }
    }
}

data class DetectionResolverConfig(
    val resolverMode: DetectionResolverMode = DetectionResolverMode.SYSTEM,
    val directDnsServers: List<String> = DnsPreset.CLOUDFLARE.servers,
    val dohPreset: DnsPreset = DnsPreset.CLOUDFLARE,
    val dohBootstrapIps: List<String> = dohPreset.servers,
    val addressFamily: DetectionAddressFamily = DetectionAddressFamily.ANY,
    val networkBinding: DetectionNetworkBinding = DetectionNetworkBinding.None,
    val connectTimeoutMillis: Long = 10_000L,
    val readTimeoutMillis: Long = 10_000L,
    val callTimeoutMillis: Long = 10_000L,
    val useNativeCurlFallback: Boolean = false,
) {
    companion object {
        fun fromActiveDnsSettings(
            settings: ActiveDnsSettings,
            addressFamily: DetectionAddressFamily = DetectionAddressFamily.ANY,
            networkBinding: DetectionNetworkBinding = DetectionNetworkBinding.None,
        ): DetectionResolverConfig =
            when {
                settings.mode == DnsModePlainUdp -> {
                    DetectionResolverConfig(
                        resolverMode = DetectionResolverMode.DIRECT,
                        directDnsServers = listOf(settings.dnsIp),
                        addressFamily = addressFamily,
                        networkBinding = networkBinding,
                    )
                }

                settings.isEncrypted &&
                    settings.encryptedDnsProtocol == EncryptedDnsProtocolDoh &&
                    settings.dohUrl.isNotBlank() -> {
                    DetectionResolverConfig(
                        resolverMode = DetectionResolverMode.DOH,
                        dohPreset =
                            DnsPreset(
                                id = settings.providerId,
                                servers = settings.dohBootstrapIps,
                                dohUrl = settings.dohUrl,
                                dohHost = settings.encryptedDnsHost,
                            ),
                        dohBootstrapIps = settings.dohBootstrapIps,
                        addressFamily = addressFamily,
                        networkBinding = networkBinding,
                    )
                }

                else -> {
                    DetectionResolverConfig(
                        resolverMode = DetectionResolverMode.SYSTEM,
                        addressFamily = addressFamily,
                        networkBinding = networkBinding,
                    )
                }
            }
    }
}

data class DetectionTransportResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

interface NativeCurlBridge {
    fun canExecute(): Boolean

    fun execute(
        request: Request,
        config: DetectionResolverConfig,
    ): DetectionTransportResponse

    object Unavailable : NativeCurlBridge {
        override fun canExecute(): Boolean = false

        override fun execute(
            request: Request,
            config: DetectionResolverConfig,
        ): DetectionTransportResponse = throw IOException("native curl bridge unavailable")
    }
}

class CombinedTransportIOException(
    okHttpError: Throwable?,
    nativeCurlError: Throwable?,
) : IOException(
        buildString {
            append("OkHttp failed")
            okHttpError?.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            append("; native curl failed")
            nativeCurlError?.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        },
        nativeCurlError ?: okHttpError,
    )

class DetectionResolverNetworkStack(
    private val baseClientFactory: () -> OkHttpClient.Builder = { OkHttpClient.Builder() },
    private val nativeCurlBridge: NativeCurlBridge = NativeCurlBridge.Unavailable,
) : DiagnosticsHttpClientFactory {
    private val lock = Any()
    private var cachedConfig: DetectionResolverConfig? = null
    private var cachedClient: OkHttpClient? = null

    override fun createClient(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
        clientFor(DetectionResolverConfig()).newBuilder().apply(configure).build()

    fun clientFor(config: DetectionResolverConfig = DetectionResolverConfig()): OkHttpClient =
        synchronized(lock) {
            val existingConfig = cachedConfig
            val existingClient = cachedClient
            if (existingConfig == config && existingClient != null) {
                return@synchronized existingClient
            }

            val client = buildClient(config)
            cachedConfig = config
            cachedClient = client
            client
        }

    fun execute(
        request: Request,
        config: DetectionResolverConfig = DetectionResolverConfig(),
    ): DetectionTransportResponse {
        var okHttpError: Throwable? = null
        try {
            clientFor(config).newCall(request).execute().use { response ->
                return DetectionTransportResponse(
                    code = response.code,
                    body = response.body.string(),
                    headers = response.headers.toMultimap(),
                )
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // Resolver fallthrough: any OkHttp failure should be retried via the native curl bridge.
            okHttpError = e
            if (!config.useNativeCurlFallback || !nativeCurlBridge.canExecute()) {
                throw e
            }
        }

        return try {
            nativeCurlBridge.execute(request, config)
        } catch (
            @Suppress("TooGenericExceptionCaught") nativeError: Throwable,
        ) {
            // Resolver fallthrough: combine both transport failures into one diagnostic exception.
            throw CombinedTransportIOException(okHttpError, nativeError)
        }
    }

    internal fun buildClient(config: DetectionResolverConfig): OkHttpClient {
        val dispatcher = Dispatcher()
        val dns = createDns(config, dispatcher)
        return baseClientFactory()
            .dispatcher(dispatcher)
            .dns(dns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .apply {
                when (val binding = config.networkBinding) {
                    is DetectionNetworkBinding.AndroidNetworkBinding -> {
                        socketFactory(binding.network.socketFactory)
                    }

                    is DetectionNetworkBinding.OsDeviceBinding -> {
                        socketFactory(
                            BindToDeviceSocketFactory(binding.interfaceName),
                        )
                    }

                    DetectionNetworkBinding.None -> {
                    }
                }
            }.build()
    }

    internal fun createDns(
        config: DetectionResolverConfig,
        dispatcher: Dispatcher,
    ): Dns {
        val baseDns =
            when (config.resolverMode) {
                DetectionResolverMode.SYSTEM -> {
                    fallbackDns(config.networkBinding)
                }

                DetectionResolverMode.DIRECT -> {
                    DirectDns(
                        servers = config.directDnsServers,
                        addressFamily = config.addressFamily,
                        binding = config.networkBinding,
                    )
                }

                DetectionResolverMode.DOH -> {
                    dohDns(config)
                }
            }
        val filtered =
            if (config.addressFamily == DetectionAddressFamily.ANY) {
                baseDns
            } else {
                FilteringDns(baseDns, config.addressFamily)
            }
        return CancellableDns(filtered, dispatcher)
    }

    private fun dohDns(config: DetectionResolverConfig): Dns {
        val bootstrapClient =
            baseClientFactory()
                .apply {
                    when (val binding = config.networkBinding) {
                        is DetectionNetworkBinding.AndroidNetworkBinding -> {
                            socketFactory(binding.network.socketFactory)
                            dns(NetworkDns(binding.network))
                        }

                        is DetectionNetworkBinding.OsDeviceBinding -> {
                            socketFactory(BindToDeviceSocketFactory(binding.interfaceName))
                        }

                        DetectionNetworkBinding.None -> {
                        }
                    }
                }.build()
        val bootstrapHosts =
            config.dohBootstrapIps
                .mapNotNull { NumericIp.parseAddress(it)?.toInetAddress() }
        val builder =
            DnsOverHttps
                .Builder()
                .client(bootstrapClient)
                .url(config.dohPreset.dohUrl.toHttpUrl())
        if (bootstrapHosts.isNotEmpty()) {
            builder.bootstrapDnsHosts(bootstrapHosts)
        }
        return builder.build()
    }

    private fun fallbackDns(binding: DetectionNetworkBinding): Dns =
        when (binding) {
            is DetectionNetworkBinding.AndroidNetworkBinding -> {
                NetworkDns(binding.network)
            }

            else -> {
                Dns.SYSTEM
            }
        }
}

internal class FilteringDns(
    private val delegate: Dns,
    private val addressFamily: DetectionAddressFamily,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val filtered =
            when (addressFamily) {
                DetectionAddressFamily.ANY -> all
                DetectionAddressFamily.IPV4 -> all.filterIsInstance<Inet4Address>()
                DetectionAddressFamily.IPV6 -> all.filterIsInstance<Inet6Address>()
            }
        if (filtered.isEmpty()) throw UnknownHostException("No $addressFamily address for $hostname")
        return filtered
    }
}

internal class CancellableDns(
    private val delegate: Dns,
    private val dispatcher: Dispatcher,
) : Dns {
    @Volatile
    private var cancelled = false

    override fun lookup(hostname: String): List<InetAddress> {
        if (cancelled) throw UnknownHostException("DNS lookup cancelled")
        return delegate.lookup(hostname)
    }

    fun cancel() {
        cancelled = true
        dispatcher.cancelAll()
    }
}

internal class NetworkDns(
    private val network: Network,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        NumericIp.parseAddress(hostname)?.let { return listOf(it.toInetAddress()) }
        return network
            .getAllByName(hostname)
            ?.toList()
            ?.distinctBy { it.hostAddress }
            ?.takeIf { it.isNotEmpty() }
            ?: throw UnknownHostException("Failed to resolve $hostname on bound network")
    }
}

internal class BindToDeviceSocketFactory(
    private val interfaceName: String,
) : SocketFactory() {
    override fun createSocket(): Socket =
        Socket().also { socket ->
            ResolverSocketBinder.bind(socket, DetectionNetworkBinding.OsDeviceBinding(interfaceName))
        }

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = Socket(host, port)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = Socket(host, port, localHost, localPort)

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = Socket(host, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = Socket(address, port, localAddress, localPort)
}

internal object ResolverSocketBinder {
    private val setsockoptIfreqMethod by lazy(LazyThreadSafetyMode.NONE) {
        Os::class.java.getMethod(
            "setsockoptIfreq",
            FileDescriptor::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
    }

    fun bind(
        socket: Socket,
        binding: DetectionNetworkBinding,
    ) {
        when (binding) {
            is DetectionNetworkBinding.AndroidNetworkBinding -> {
                binding.network.bindSocket(socket)
            }

            is DetectionNetworkBinding.OsDeviceBinding -> {
                ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
                    bindFileDescriptorToDevice(descriptor.fileDescriptor, binding.interfaceName)
                }
            }

            DetectionNetworkBinding.None -> {
            }
        }
    }

    fun bind(
        socket: DatagramSocket,
        binding: DetectionNetworkBinding,
    ) {
        when (binding) {
            is DetectionNetworkBinding.AndroidNetworkBinding -> {
                binding.network.bindSocket(socket)
            }

            is DetectionNetworkBinding.OsDeviceBinding -> {
                ParcelFileDescriptor.fromDatagramSocket(socket).use { descriptor ->
                    bindFileDescriptorToDevice(descriptor.fileDescriptor, binding.interfaceName)
                }
            }

            DetectionNetworkBinding.None -> {
            }
        }
    }

    private fun bindFileDescriptorToDevice(
        fd: FileDescriptor,
        interfaceName: String,
    ) {
        setsockoptIfreqMethod.invoke(
            null,
            fd,
            OsConstants.SOL_SOCKET,
            OsConstants.SO_BINDTODEVICE,
            interfaceName,
        )
    }
}
