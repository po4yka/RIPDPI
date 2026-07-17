package com.poyka.ripdpi.e2e

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

internal const val TestNetworkProbeServiceClassName = "com.poyka.ripdpi.e2e.TestNetworkProbeService"
internal const val TestNetworkProbeServiceDescriptor = "com.poyka.ripdpi.e2e.TestNetworkProbeService"
internal const val TestNetworkProbeDnsTransactionCode = IBinder.FIRST_CALL_TRANSACTION
private const val ExpectedProbeCallerPackage = "com.poyka.ripdpi"

class TestNetworkProbeService : Service() {
    private val binder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != TestNetworkProbeDnsTransactionCode || reply == null) {
                    return super.onTransact(code, data, reply, flags)
                }
                if (!isExpectedCaller(Binder.getCallingUid())) {
                    throw SecurityException("Test network probe rejected an unexpected caller")
                }

                data.enforceInterface(TestNetworkProbeServiceDescriptor)
                val request =
                    Intent(ActionProbeDns).apply {
                        putExtra(ExtraHost, data.readString())
                        putExtra(ExtraPort, data.readInt())
                        putExtra(ExtraReadTimeoutMs, data.readInt())
                        putExtra(ExtraQueryHost, data.readString())
                        putExtra(ExtraProbeSignalId, data.readString())
                        putExtras(
                            Bundle().apply {
                                putBinder(ExtraProbeSignalBinder, data.readStrongBinder())
                            },
                        )
                    }
                val extras = Bundle()
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
