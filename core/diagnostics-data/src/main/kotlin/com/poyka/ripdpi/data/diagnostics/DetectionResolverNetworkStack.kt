package com.poyka.ripdpi.data.diagnostics

import android.net.Network
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.BundledDnsProviderCatalog
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.net.DatagramPacket
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
                .filter(::isValidIpLiteral)
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
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

internal object DnsWireCodec {
    fun buildQuery(
        hostname: String,
        recordType: DnsRecordType,
        transactionId: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeShort(transactionId)
            stream.writeShort(DNS_FLAGS_RECURSION_DESIRED)
            stream.writeShort(DNS_SINGLE_QUESTION_COUNT)
            stream.writeShort(0)
            stream.writeShort(0)
            stream.writeShort(0)
            hostname.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.UTF_8)
                stream.writeByte(bytes.size)
                stream.write(bytes)
            }
            stream.writeByte(0)
            stream.writeShort(recordType.wireCode)
            stream.writeShort(1)
        }
        return output.toByteArray()
    }

    // Multiple validation throws are idiomatic for a wire-format parser.
    @Suppress("ThrowsCount")
    fun parseResponse(
        bytes: ByteArray,
        transactionId: Int,
        recordType: DnsRecordType,
    ): List<InetAddress> {
        if (bytes.size < DNS_HEADER_SIZE) throw IOException("DNS response too short")
        val responseId = readUnsignedShort(bytes, DNS_ID_OFFSET)
        if (responseId != transactionId) throw IOException("Unexpected DNS response id")

        val flags = readUnsignedShort(bytes, DNS_FLAGS_OFFSET)
        val rcode = flags and DNS_RCODE_MASK
        if (rcode == DNS_RCODE_NXDOMAIN) throw UnknownHostException("NXDOMAIN")
        if (rcode != DNS_RCODE_NO_ERROR) throw IOException("DNS server error $rcode")

        val questionCount = readUnsignedShort(bytes, DNS_QDCOUNT_OFFSET)
        val answerCount = readUnsignedShort(bytes, DNS_ANCOUNT_OFFSET)
        var offset = DNS_HEADER_SIZE

        repeat(questionCount) {
            offset = skipName(bytes, offset) + DNS_QUESTION_TRAILER_SIZE
            if (offset > bytes.size) throw IOException("Malformed DNS question")
        }

        val addresses = mutableListOf<InetAddress>()
        repeat(answerCount) {
            offset = skipName(bytes, offset)
            if (offset + DNS_ANSWER_HEADER_SIZE > bytes.size) throw IOException("Malformed DNS answer")
            val answerType = readUnsignedShort(bytes, offset)
            val answerClass = readUnsignedShort(bytes, offset + DNS_ANSWER_CLASS_OFFSET)
            val rdLength = readUnsignedShort(bytes, offset + DNS_ANSWER_RDLENGTH_OFFSET)
            offset += DNS_ANSWER_HEADER_SIZE
            if (offset + rdLength > bytes.size) throw IOException("Malformed DNS payload")

            val isIpv4Answer =
                answerClass == DNS_CLASS_IN &&
                    answerType == DnsRecordType.A.wireCode &&
                    rdLength == IPV4_LENGTH
            val isIpv6Answer =
                answerClass == DNS_CLASS_IN &&
                    answerType == DnsRecordType.AAAA.wireCode &&
                    rdLength == IPV6_LENGTH
            val address =
                when {
                    isIpv4Answer -> {
                        InetAddress.getByAddress(bytes.copyOfRange(offset, offset + rdLength))
                    }

                    isIpv6Answer -> {
                        InetAddress.getByAddress(bytes.copyOfRange(offset, offset + rdLength))
                    }

                    else -> {
                        null
                    }
                }
            if (address != null && address.matches(recordType)) {
                addresses += address
            }
            offset += rdLength
        }
        return addresses.distinctBy { it.hostAddress }
    }

    private fun InetAddress.matches(recordType: DnsRecordType): Boolean =
        when (recordType) {
            DnsRecordType.A -> {
                this is Inet4Address
            }

            DnsRecordType.AAAA -> {
                this is Inet6Address
            }
        }

    private fun skipName(
        bytes: ByteArray,
        startOffset: Int,
    ): Int {
        var offset = startOffset
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and BYTE_MASK
            if (length == 0) return offset + 1
            if ((length and DNS_COMPRESSION_POINTER_MASK) == DNS_COMPRESSION_POINTER_MASK) {
                if (offset + 1 >= bytes.size) throw IOException("Malformed compression pointer")
                return offset + DNS_COMPRESSION_POINTER_SIZE
            }
            offset += 1 + length
        }
        throw IOException("Malformed DNS name")
    }

    private fun readUnsignedShort(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        val highByte = (bytes[offset].toInt() and BYTE_MASK) shl BYTE_SHIFT
        val lowByte = bytes[offset + 1].toInt() and BYTE_MASK
        return highByte or lowByte
    }

    private const val DNS_HEADER_SIZE = 12
    private const val DNS_QUESTION_TRAILER_SIZE = 4
    private const val DNS_ANSWER_HEADER_SIZE = 10
    private const val DNS_CLASS_IN = 1
    private const val IPV4_LENGTH = 4
    private const val IPV6_LENGTH = 16

    private const val DNS_ID_OFFSET = 0
    private const val DNS_FLAGS_OFFSET = 2
    private const val DNS_QDCOUNT_OFFSET = 4
    private const val DNS_ANCOUNT_OFFSET = 6
    private const val DNS_ANSWER_CLASS_OFFSET = 2
    private const val DNS_ANSWER_RDLENGTH_OFFSET = 8
    private const val DNS_FLAGS_RECURSION_DESIRED = 0x0100
    private const val DNS_SINGLE_QUESTION_COUNT = 1
    private const val DNS_RCODE_MASK = 0x000F
    private const val DNS_RCODE_NO_ERROR = 0
    private const val DNS_RCODE_NXDOMAIN = 3
    private const val DNS_COMPRESSION_POINTER_MASK = 0xC0
    private const val DNS_COMPRESSION_POINTER_SIZE = 2
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
}

internal class DirectDns(
    servers: List<String>,
    private val addressFamily: DetectionAddressFamily = DetectionAddressFamily.ANY,
    private val port: Int = 53,
    private val timeoutMillis: Int = 3_000,
    private val binding: DetectionNetworkBinding = DetectionNetworkBinding.None,
) : Dns {
    private val serverAddresses =
        servers.mapNotNull { value ->
            value
                .trim()
                .takeIf(::isValidIpLiteral)
                ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isValidIpLiteral(hostname)) return listOf(InetAddress.getByName(hostname))
        if (serverAddresses.isEmpty()) throw UnknownHostException("No valid direct DNS servers configured")

        val resolved = linkedMapOf<String, InetAddress>()
        var lastFailure: Exception? = null
        recordTypes().forEach { recordType ->
            lastFailure = queryAllServers(hostname, recordType, resolved) ?: lastFailure
        }
        if (resolved.isNotEmpty()) return resolved.values.toList()
        throw UnknownHostException(lastFailure?.message ?: "Failed to resolve $hostname")
    }

    /**
     * Queries each configured server for [recordType], adding any answers to [resolved].
     * Returns the last failure encountered, or null if every server query succeeded.
     */
    private fun queryAllServers(
        hostname: String,
        recordType: DnsRecordType,
        resolved: MutableMap<String, InetAddress>,
    ): Exception? {
        var lastFailure: Exception? = null
        for (server in serverAddresses) {
            try {
                val addresses = query(server, hostname, recordType)
                addresses.forEach { address ->
                    resolved[address.hostAddress ?: return@forEach] = address
                }
                if (addresses.isNotEmpty()) {
                    break
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Resolver fallthrough: any failure for this server means try the next one.
                lastFailure = e
            }
        }
        return lastFailure
    }

    private fun recordTypes(): List<DnsRecordType> =
        when (addressFamily) {
            DetectionAddressFamily.ANY -> listOf(DnsRecordType.A, DnsRecordType.AAAA)
            DetectionAddressFamily.IPV4 -> listOf(DnsRecordType.A)
            DetectionAddressFamily.IPV6 -> listOf(DnsRecordType.AAAA)
        }

    private fun query(
        server: InetAddress,
        hostname: String,
        recordType: DnsRecordType,
    ): List<InetAddress> {
        val transactionId = (System.nanoTime().toInt() and TRANSACTION_ID_MASK)
        val payload = DnsWireCodec.buildQuery(hostname, recordType, transactionId)
        DatagramSocket().use { socket ->
            ResolverSocketBinder.bind(socket, binding)
            socket.connect(server, port)
            socket.soTimeout = timeoutMillis
            socket.send(DatagramPacket(payload, payload.size))
            val responseBuffer = ByteArray(DNS_RESPONSE_BUFFER_SIZE)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            while (true) {
                // A SocketTimeoutException from receive() propagates to the caller unchanged.
                socket.receive(responsePacket)
                if (responsePacket.address == server && responsePacket.port == port) {
                    return DnsWireCodec.parseResponse(
                        bytes = responsePacket.data.copyOf(responsePacket.length),
                        transactionId = transactionId,
                        recordType = recordType,
                    )
                }
            }
        }
    }

    private companion object {
        const val DNS_RESPONSE_BUFFER_SIZE = 1500
        const val TRANSACTION_ID_MASK = 0xFFFF
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
        if (isValidIpLiteral(hostname)) return listOf(InetAddress.getByName(hostname))
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

private fun isValidIpLiteral(value: String): Boolean {
    val trimmed = value.trim()
    return IPV4_LITERAL.matches(trimmed) || trimmed.contains(":")
}

private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
