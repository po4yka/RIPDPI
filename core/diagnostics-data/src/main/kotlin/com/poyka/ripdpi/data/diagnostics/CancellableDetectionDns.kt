package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.NumericIp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun resolveDnsAddresses(
    dns: Dns,
    hostname: String,
    timeoutMillis: Long,
    addressFamily: DetectionAddressFamily = DetectionAddressFamily.ANY,
): List<InetAddress> {
    NumericIp.parseAddress(hostname)?.let {
        val address = it.toInetAddress()
        if (!address.matchesFamily(addressFamily)) throw UnknownHostException("No $addressFamily address for $hostname")
        return listOf(address)
    }
    return if (timeoutMillis > 0) {
        withTimeoutOrNull(timeoutMillis) { awaitDnsAddresses(dns, hostname) }
            ?: throw UnknownHostException("DNS query deadline exceeded")
    } else {
        awaitDnsAddresses(dns, hostname)
    }
}

private suspend fun awaitDnsAddresses(
    dns: Dns,
    hostname: String,
): List<InetAddress> =
    suspendCancellableCoroutine { continuation ->
        val call = dns.newCall(Dns.Request(hostname))
        val addresses = mutableListOf<InetAddress>()
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Dns.Callback {
                override fun onRecords(
                    call: Dns.Call,
                    last: Boolean,
                    records: List<Dns.Record>,
                ) {
                    addresses += records.filterIsInstance<Dns.Record.IpAddress>().map { it.address }
                    if (last) {
                        if (addresses.isEmpty()) {
                            continuation.resumeWithException(UnknownHostException("No addresses for $hostname"))
                        } else {
                            continuation.resume(addresses.distinctBy { it.hostAddress })
                        }
                    }
                }

                override fun onFailure(
                    call: Dns.Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }
            },
        )
    }

internal class FilteringDnsCall(
    private val delegate: Dns.Call,
    private val addressFamily: DetectionAddressFamily,
) : Dns.Call by delegate {
    override fun enqueue(callback: Dns.Callback) {
        delegate.enqueue(
            object : Dns.Callback {
                override fun onRecords(
                    call: Dns.Call,
                    last: Boolean,
                    records: List<Dns.Record>,
                ) {
                    val filtered =
                        records.filterIsInstance<Dns.Record.IpAddress>().filter {
                            it.address.matchesFamily(
                                addressFamily,
                            )
                        }
                    callback.onRecords(this@FilteringDnsCall, last, filtered)
                }

                override fun onFailure(
                    call: Dns.Call,
                    e: IOException,
                ) {
                    callback.onFailure(this@FilteringDnsCall, e)
                }
            },
        )
    }
}

private fun InetAddress.matchesFamily(addressFamily: DetectionAddressFamily): Boolean =
    when (addressFamily) {
        DetectionAddressFamily.ANY -> true
        DetectionAddressFamily.IPV4 -> this is Inet4Address
        DetectionAddressFamily.IPV6 -> this is Inet6Address
    }
