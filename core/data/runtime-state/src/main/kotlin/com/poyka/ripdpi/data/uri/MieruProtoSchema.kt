package com.poyka.ripdpi.data.uri

// Mieru v3.36.0 (155ebbd60): protobuf wire constants and supported client schema.
internal const val MieruMaxPort = 65535

// Field numbers from upstream pkg/appctl/proto (clientcfg.proto, base.proto).
internal const val FieldConfigProfiles = 1
internal const val FieldConfigActiveProfile = 2
internal const val FieldProfileName = 1
internal const val FieldProfileUser = 2
internal const val FieldProfileServers = 3
internal const val FieldProfileMtu = 4
internal const val FieldProfileMultiplexing = 5
internal const val FieldProfileHandshake = 6
internal const val FieldProfileTrafficPattern = 7
internal const val FieldUserName = 1
internal const val FieldUserPassword = 2
internal const val FieldServerIp = 1
internal const val FieldServerDomain = 2
internal const val FieldServerBindings = 3
internal const val FieldBindingPort = 1
internal const val FieldBindingProtocol = 2
internal const val FieldBindingRange = 3
internal const val FieldMultiplexingLevel = 1

// Upstream MultiplexingLevel enum values.
internal const val LevelDefault = 0L
internal const val LevelOff = 1L
internal const val LevelLow = 2L
internal const val LevelMiddle = 3L
internal const val LevelHigh = 4L

internal const val Base64GroupSize = 4
internal const val ProtoTransportTcp = 2L

// Pinned clientcfg.proto and base.proto fields that do not change the selected TCP profile.
internal const val FieldConfigRpcPort = 3
internal const val FieldConfigSocks5Port = 4
internal const val FieldConfigAdvancedSettings = 5
internal const val FieldConfigLoggingLevel = 6
internal const val FieldConfigSocks5ListenLan = 7
internal const val FieldConfigHttpProxyPort = 8
internal const val FieldConfigHttpProxyListenLan = 9
internal const val FieldConfigSocks5Authentication = 10
internal const val FieldUserHashedPassword = 3
internal const val FieldUserQuotas = 4
internal const val FieldUserAllowPrivateIp = 5
internal const val FieldUserAllowLoopbackIp = 6
internal const val ProtoTransportUdp = 1L
internal const val HandshakeDefaultNumber = 0
internal const val HandshakeStandardNumber = 1
internal const val HandshakeNoWaitNumber = 2
internal const val MieruMaximumUriBytes = 2 * 1024 * 1024
internal const val MieruMaximumFields = 4096
internal const val MieruPasswordHashBytes = 32
internal const val MieruHexCharsPerByte = 2
internal const val MieruHexRadix = 16

internal val MieruConfigVarints =
    setOf(
        FieldConfigRpcPort,
        FieldConfigSocks5Port,
        FieldConfigLoggingLevel,
        FieldConfigSocks5ListenLan,
        FieldConfigHttpProxyPort,
        FieldConfigHttpProxyListenLan,
    )
internal val MieruConfigBytes =
    setOf(
        FieldConfigProfiles,
        FieldConfigActiveProfile,
        FieldConfigAdvancedSettings,
        FieldConfigSocks5Authentication,
    )

internal const val ProtoVarintBits = 7
internal const val ProtoVarintPayloadMask = 0x7f
internal const val ProtoVarintContinuation = 0x80
internal const val ProtoTagTypeMask = 7
internal const val ProtoTagTypeBits = 3
internal const val ProtoVarintMaxBytes = 10
internal const val ProtoWireVarint = 0
internal const val ProtoWireFixed64 = 1
internal const val ProtoWireLen = 2
internal const val ProtoWireFixed32 = 5

internal const val ProtoVarintFinalIndex = ProtoVarintMaxBytes - 1
internal const val ProtoVarintOverflowMask = 0xfe
internal const val ProtoMaximumFieldNumber = 0x1fffffffL
