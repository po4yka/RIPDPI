package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LocalObservationState {
    @SerialName("enabled")
    ENABLED,

    @SerialName("disabled")
    DISABLED,

    @SerialName("unknown")
    UNKNOWN,

    @SerialName("permission_denied")
    PERMISSION_DENIED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

@Serializable
enum class LocalTransport {
    @SerialName("wifi")
    WIFI,

    @SerialName("cellular")
    CELLULAR,

    @SerialName("vpn")
    VPN,

    @SerialName("none")
    NONE,

    @SerialName("other")
    OTHER,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class LocalDataSaverState {
    @SerialName("disabled")
    DISABLED,

    @SerialName("enabled")
    ENABLED,

    @SerialName("exempt")
    EXEMPT,

    @SerialName("unknown")
    UNKNOWN,

    @SerialName("permission_denied")
    PERMISSION_DENIED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

@Serializable
enum class LocalSimScope {
    @SerialName("default_data")
    DEFAULT_DATA,

    @SerialName("not_configured")
    NOT_CONFIGURED,

    @SerialName("changed")
    CHANGED,

    @SerialName("permission_denied")
    PERMISSION_DENIED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

@Serializable
enum class LocalSimState {
    @SerialName("ready")
    READY,

    @SerialName("loaded")
    LOADED,

    @SerialName("absent")
    ABSENT,

    @SerialName("pin_required")
    PIN_REQUIRED,

    @SerialName("puk_required")
    PUK_REQUIRED,

    @SerialName("network_locked")
    NETWORK_LOCKED,

    @SerialName("not_ready")
    NOT_READY,

    @SerialName("permanently_disabled")
    PERMANENTLY_DISABLED,

    @SerialName("card_io_error")
    CARD_IO_ERROR,

    @SerialName("card_restricted")
    CARD_RESTRICTED,

    @SerialName("unknown")
    UNKNOWN,

    @SerialName("permission_denied")
    PERMISSION_DENIED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

@Serializable
enum class LocalServiceState {
    @SerialName("in_service")
    IN_SERVICE,

    @SerialName("out_of_service")
    OUT_OF_SERVICE,

    @SerialName("emergency_only")
    EMERGENCY_ONLY,

    @SerialName("power_off")
    POWER_OFF,

    @SerialName("unknown")
    UNKNOWN,

    @SerialName("permission_denied")
    PERMISSION_DENIED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

@Serializable
enum class LocalDataConnectionState {
    @SerialName("connected")
    CONNECTED,

    @SerialName("connecting")
    CONNECTING,

    @SerialName("disconnected")
    DISCONNECTED,

    @SerialName("suspended")
    SUSPENDED,

    @SerialName("disconnecting")
    DISCONNECTING,

    @SerialName("handover")
    HANDOVER,

    @SerialName("unknown")
    UNKNOWN,

    @SerialName("permission_denied")
    PERMISSION_DENIED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unavailable")
    UNAVAILABLE,
}
