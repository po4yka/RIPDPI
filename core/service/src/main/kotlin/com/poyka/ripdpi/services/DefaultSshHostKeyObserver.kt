package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.core.RipDpiSshHostKeyProbe
import com.poyka.ripdpi.core.SshHostKeyProbeFailure
import com.poyka.ripdpi.core.SshHostKeyProbeRequest
import com.poyka.ripdpi.core.SshHostKeyProbeResult
import com.poyka.ripdpi.core.SshProbeSocketController
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultSshHostKeyObserver
    constructor(
        private val context: Context,
        private val native: RipDpiSshHostKeyProbe,
        scope: CoroutineScope,
        private val dispatchers: AppCoroutineDispatchers,
        private val resolveAddresses: (Network, String) -> Array<InetAddress>,
    ) : SshHostKeyObserver {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            native: RipDpiSshHostKeyProbe,
            @ApplicationScope scope: CoroutineScope,
            dispatchers: AppCoroutineDispatchers,
        ) : this(context, native, scope, dispatchers, Network::getAllByName)

        private val runner = SshProbeOperationRunner(scope, dispatchers.io, ObservationTimeoutMillis)

        override suspend fun observe(
            server: String,
            port: Int,
        ): SshHostKeyProbeResult {
            if (!validEndpoint(server, port)) return failed(SshHostKeyProbeFailure.InvalidInput)
            return runner.run { lease -> observeOwned(server, port, lease) }
        }

        private suspend fun observeOwned(
            server: String,
            port: Int,
            lease: SshProbeOperationLease,
        ): SshHostKeyProbeResult {
            val binding = SshProbeServiceBinding(context, lease)
            return try {
                withTimeout(ObservationTimeoutMillis) {
                    val networkPermission =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_NETWORK_STATE,
                        )
                    if (networkPermission != PackageManager.PERMISSION_GRANTED || VpnService.prepare(context) != null) {
                        return@withTimeout failed(SshHostKeyProbeFailure.ProtectionDenied)
                    }
                    withContext(dispatchers.main) { binding.bind() }
                    val service = binding.awaitService()
                    SshProbeUnderlayMonitor(context).use { underlay ->
                        underlay.start()
                        val snapshot =
                            withTimeoutOrNull(UnderlayTimeoutMillis) { underlay.awaitEligible() }
                                ?: return@withTimeout failed(SshHostKeyProbeFailure.NoUnderlay)
                        observeOnNetwork(server, port, lease, service, underlay, snapshot)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                failed(SshHostKeyProbeFailure.Timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                failed(SshHostKeyProbeFailure.ProtectionDenied)
            } catch (_: IOException) {
                failed(SshHostKeyProbeFailure.ConnectFailed)
            } catch (_: IllegalStateException) {
                failed(SshHostKeyProbeFailure.InternalFailure)
            } catch (_: LinkageError) {
                failed(SshHostKeyProbeFailure.InternalFailure)
            } finally {
                withContext(NonCancellable + dispatchers.main) { binding.close() }
            }
        }

        private suspend fun observeOnNetwork(
            server: String,
            port: Int,
            lease: SshProbeOperationLease,
            service: SshHostKeyProbeService,
            underlay: SshProbeUnderlayMonitor,
            snapshot: ResolverUnderlaySnapshot<Network>,
        ): SshHostKeyProbeResult {
            val deadline = SystemClock.elapsedRealtime() + ExchangeTimeoutMillis

            fun isCurrent(): Boolean = lease.isActive() && service.isPrepared() && underlay.snapshot() == snapshot
            val addresses =
                resolveOnCurrentUnderlay(
                    snapshot = { underlay.snapshot().takeIf { isCurrent() } },
                    resolve = { network -> resolveAddresses(network, server) },
                ) ?: return failed(SshHostKeyProbeFailure.NetworkChanged)
            currentCoroutineContext().ensureActive()
            return probeAddresses(
                addresses.take(MaxAddresses).mapNotNull { it.hostAddress },
                port,
                deadline,
                ::isCurrent,
            ) { fd ->
                isCurrent() && service.protect(fd) &&
                    runCatching {
                        ParcelFileDescriptor.fromFd(fd).use { snapshot.network.bindSocket(it.fileDescriptor) }
                        isCurrent()
                    }.getOrDefault(false)
            }
        }

        private suspend fun probeAddresses(
            addresses: List<String>,
            port: Int,
            deadline: Long,
            isCurrent: () -> Boolean,
            socketController: SshProbeSocketController,
        ): SshHostKeyProbeResult {
            var result: SshHostKeyProbeResult = failed(SshHostKeyProbeFailure.ConnectFailed)
            for (address in addresses) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                result =
                    when {
                        !isCurrent() -> failed(SshHostKeyProbeFailure.NetworkChanged)
                        remaining <= 0 -> failed(SshHostKeyProbeFailure.Timeout)
                        else -> native.probe(SshHostKeyProbeRequest(address, port, remaining.toInt()), socketController)
                    }
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) result = failed(SshHostKeyProbeFailure.NetworkChanged)
                if (result != failed(SshHostKeyProbeFailure.ConnectFailed)) break
            }
            return result
        }

        private fun validEndpoint(
            server: String,
            port: Int,
        ): Boolean =
            server.isNotBlank() && server.length <= MaxHostLength && port in 1..MaxPort &&
                server.none { it.isWhitespace() || it.isISOControl() || it == '/' }

        private fun failed(reason: SshHostKeyProbeFailure) = SshHostKeyProbeResult.Failed(reason)

        private companion object {
            const val ObservationTimeoutMillis = 10_000L
            const val UnderlayTimeoutMillis = 2_000L
            const val ExchangeTimeoutMillis = 5_000L
            const val MaxAddresses = 8
            const val MaxHostLength = 253
            const val MaxPort = 65_535
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SshHostKeyObserverModule {
    @Binds
    @Singleton
    abstract fun bindSshHostKeyObserver(observer: DefaultSshHostKeyObserver): SshHostKeyObserver
}
