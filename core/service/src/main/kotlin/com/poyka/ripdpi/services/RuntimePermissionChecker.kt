package com.poyka.ripdpi.services

data class RuntimePermissionState(
    val notificationsGranted: Boolean,
    val vpnConsentGranted: Boolean,
)

interface RuntimePermissionChecker {
    fun check(): RuntimePermissionState
}
