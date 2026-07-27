package com.poyka.ripdpi.e2e

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock

internal const val TestNetworkProbeServiceClassName = "com.poyka.ripdpi.e2e.TestNetworkProbeService"
internal const val TestNetworkProbeServiceDescriptor = "com.poyka.ripdpi.e2e.TestNetworkProbeService"
internal const val TestNetworkProbeDnsTransactionCode = IBinder.FIRST_CALL_TRANSACTION
internal const val TestNetworkProbeAwaitVpnTransactionCode = IBinder.FIRST_CALL_TRANSACTION + 1
internal const val TestNetworkProbeIsVpnTransactionCode = IBinder.FIRST_CALL_TRANSACTION + 2
private const val ExpectedProbeCallerPackage = "com.poyka.ripdpi"
private const val DefaultNetworkPollIntervalMs = 25L
private const val StableVpnObservationMs = 100L

class TestNetworkProbeService : Service() {
    private val binder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (
                    reply == null ||
                    (
                        code != TestNetworkProbeDnsTransactionCode &&
                            code != TestNetworkProbeAwaitVpnTransactionCode &&
                            code != TestNetworkProbeIsVpnTransactionCode
                    )
                ) {
                    return super.onTransact(code, data, reply, flags)
                }
                if (!isExpectedCaller(Binder.getCallingUid())) {
                    throw SecurityException("Test network probe rejected an unexpected caller")
                }

                data.enforceInterface(TestNetworkProbeServiceDescriptor)
                if (code == TestNetworkProbeAwaitVpnTransactionCode) {
                    val timeoutMs = data.readInt()
                    reply.writeNoException()
                    reply.writeInt(if (awaitVpnDefaultNetwork(timeoutMs.toLong())) 1 else 0)
                    return true
                }
                if (code == TestNetworkProbeIsVpnTransactionCode) {
                    reply.writeNoException()
                    reply.writeInt(if (isVpnDefaultNetwork()) 1 else 0)
                    return true
                }
                val request =
                    Intent(ActionProbeDns).apply {
                        putExtra(ExtraHost, data.readString())
                        putExtra(ExtraPort, data.readInt())
                        putExtra(ExtraReadTimeoutMs, data.readInt())
                        putExtra(ExtraQueryHost, data.readString())
                        putExtra(ExtraDnsQueryType, data.readInt())
                        putExtra(ExtraDnsTransport, data.readString())
                        putExtra(ExtraProbeSignalId, data.readString())
                        putExtras(
                            Bundle().apply {
                                putBinder(ExtraProbeSignalBinder, data.readStrongBinder())
                            },
                        )
                    }
                val extras = Bundle()
                extras.putInt(ExtraProbePid, Process.myPid())
                extras.putInt(ExtraProbeUid, Process.myUid())
                val callingIdentity = clearCallingIdentity()
                val resultCode =
                    try {
                        try {
                            TestNetworkProbeReceiver().runDnsProbe(request, extras)
                            Activity.RESULT_OK
                        } catch (error: Throwable) {
                            extras.putBoolean(ExtraOk, false)
                            extras.putString(ExtraErrorClass, error.javaClass.name)
                            extras.putString(ExtraErrorMessage, error.message)
                            Activity.RESULT_CANCELED
                        }
                    } finally {
                        restoreCallingIdentity(callingIdentity)
                    }

                reply.writeNoException()
                reply.writeInt(resultCode)
                reply.writeBundle(extras)
                return true
            }
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun awaitVpnDefaultNetwork(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var vpnObservedAt = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            if (isVpnDefaultNetwork()) {
                if (vpnObservedAt == 0L) {
                    vpnObservedAt = now
                } else if (now - vpnObservedAt >= StableVpnObservationMs) {
                    return true
                }
            } else {
                vpnObservedAt = 0L
            }
            Thread.sleep(DefaultNetworkPollIntervalMs)
        }
        return false
    }

    private fun isVpnDefaultNetwork(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun isExpectedCaller(callingUid: Int): Boolean {
        val callerPackages = packageManager.getPackagesForUid(callingUid) ?: return false
        var expectedPackageFound = false
        for (callerPackage in callerPackages) {
            if (ExpectedProbeCallerPackage.equals(callerPackage)) {
                expectedPackageFound = true
                break
            }
        }
        return expectedPackageFound &&
            packageManager.checkSignatures(callingUid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH
    }
}
