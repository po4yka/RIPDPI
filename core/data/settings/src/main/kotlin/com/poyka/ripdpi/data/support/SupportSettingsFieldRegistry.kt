package com.poyka.ripdpi.data.support

import com.google.protobuf.MessageLite
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import com.poyka.ripdpi.proto.StrategyUdpStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.lang.reflect.Method
import java.util.Base64

private const val SettingsPathPrefix = "settings."
private const val Base64BlockSize = 4
private const val Base64PaddingOffset = Base64BlockSize - 1

data class SupportSettingsFieldChange(
    val path: String,
    val previous: String,
    val next: String,
    val sensitivity: SupportSettingsSensitivity,
)

enum class SupportSettingsSensitivity {
    Standard,
    Sensitive,
}

sealed interface SupportSettingsFieldResult {
    data class Success(
        val change: SupportSettingsFieldChange,
    ) : SupportSettingsFieldResult

    data class Error(
        val path: String,
        val reason: SupportSettingsFieldErrorReason,
    ) : SupportSettingsFieldResult
}

enum class SupportSettingsFieldErrorReason {
    UnsupportedPath,
    UnsupportedOperation,
    InvalidValue,
}

object SupportSettingsFieldRegistry {
    private val fields: Map<String, SupportSettingsField> by lazy { buildFields() }

    val supportedPaths: Set<String>
        get() = fields.keys

    fun apply(
        builder: AppSettings.Builder,
        operation: SupportSettingsOperation,
    ): SupportSettingsFieldResult {
        val field = normalizePath(operation.path)?.let(fields::get)
        return when {
            operation.op != SupportSettingsOpSet -> {
                SupportSettingsFieldResult.Error(
                    operation.path,
                    SupportSettingsFieldErrorReason.UnsupportedOperation,
                )
            }

            field == null -> {
                SupportSettingsFieldResult.Error(
                    operation.path,
                    SupportSettingsFieldErrorReason.UnsupportedPath,
                )
            }

            else -> {
                field.apply(builder, operation.value)
            }
        }
    }

    private fun buildFields(): Map<String, SupportSettingsField> {
        val builderClass = AppSettings.Builder::class.java
        val fields = linkedMapOf<String, SupportSettingsField>()
        builderClass.methods
            .filter { method -> method.name.startsWith("set") && method.parameterTypes.size == 1 }
            .filter { method -> method.returnType == builderClass }
            .filterNot(::isInternalSetter)
            .forEach { method ->
                val suffix = method.name.removePrefix("set")
                fields[pathForSuffix(suffix)] = ScalarSupportSettingsField(suffix, method)
            }
        builderClass.methods
            .filter { method -> method.name.startsWith("addAll") && method.parameterTypes.size == 1 }
            .filter { method -> method.returnType == builderClass }
            .filterNot(::isInternalSetter)
            .forEach { method ->
                val suffix = method.name.removePrefix("addAll")
                val clearMethod = builderClass.getMethod("clear$suffix")
                fields[pathForSuffix(suffix)] = RepeatedSupportSettingsField(suffix, clearMethod, method)
            }
        return fields
    }

    private fun isInternalSetter(method: Method): Boolean {
        val name = method.name
        return name == "setUnknownFields" || name == "setField" || name == "setRepeatedField"
    }

    private fun normalizePath(path: String): String? {
        val trimmed = path.trim()
        return trimmed
            .takeIf { it.startsWith(SettingsPathPrefix) }
            ?.removePrefix(SettingsPathPrefix)
            ?.takeIf(String::isNotBlank)
            ?.let { rawName -> SettingsPathPrefix + rawName.toSnakeCase() }
    }

    private fun pathForSuffix(suffix: String): String = SettingsPathPrefix + suffix.toSnakeCase()
}

private sealed class SupportSettingsField(
    private val suffix: String,
) {
    val path: String = SettingsPathPrefix + suffix.toSnakeCase()

    abstract fun apply(
        builder: AppSettings.Builder,
        value: JsonElement,
    ): SupportSettingsFieldResult

    protected fun before(builder: AppSettings.Builder): String = displayValue(readCurrentValue(builder))

    protected fun change(
        previous: String,
        next: String,
    ): SupportSettingsFieldResult.Success =
        SupportSettingsFieldResult.Success(
            SupportSettingsFieldChange(
                path = path,
                previous = previous,
                next = next,
                sensitivity = sensitivityFor(path),
            ),
        )

    protected fun readCurrentValue(builder: AppSettings.Builder): Any? {
        val message = builder.build()
        val getter = getter(message.javaClass, suffix)
        return getter?.invoke(message)
    }
}

private class ScalarSupportSettingsField(
    suffix: String,
    private val setter: Method,
) : SupportSettingsField(suffix) {
    override fun apply(
        builder: AppSettings.Builder,
        value: JsonElement,
    ): SupportSettingsFieldResult {
        val previous = before(builder)
        val parsed =
            parseScalar(value, setter.parameterTypes.single())
                ?: return SupportSettingsFieldResult.Error(path, SupportSettingsFieldErrorReason.InvalidValue)
        setter.invoke(builder, parsed)
        return change(previous, displayValue(parsed))
    }
}

private class RepeatedSupportSettingsField(
    suffix: String,
    private val clear: Method,
    private val addAll: Method,
) : SupportSettingsField(suffix) {
    override fun apply(
        builder: AppSettings.Builder,
        value: JsonElement,
    ): SupportSettingsFieldResult {
        val previous = before(builder)
        val parsed =
            parseRepeated(path, value)
                ?: return SupportSettingsFieldResult.Error(path, SupportSettingsFieldErrorReason.InvalidValue)
        clear.invoke(builder)
        addAll.invoke(builder, parsed)
        return change(previous, displayValue(parsed))
    }
}

private fun parseScalar(
    value: JsonElement,
    type: Class<*>,
): Any? =
    when (type) {
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaObjectType,
        -> primitive(value)?.booleanOrNull

        Int::class.javaPrimitiveType,
        Int::class.javaObjectType,
        -> primitive(value)?.intOrNull

        Long::class.javaPrimitiveType,
        Long::class.javaObjectType,
        -> primitive(value)?.longOrNull

        Double::class.javaPrimitiveType,
        Double::class.javaObjectType,
        -> primitive(value)?.doubleOrNull

        String::class.java -> primitive(value)?.contentOrNull

        StrategyTcpStep::class.java -> parseMessage(value, StrategyTcpStep::parseFrom)

        StrategyUdpStep::class.java -> parseMessage(value, StrategyUdpStep::parseFrom)

        else -> parseUnsupportedMessage(value, type)
    }

private fun primitive(value: JsonElement): JsonPrimitive? = value as? JsonPrimitive

private fun parseRepeated(
    path: String,
    value: JsonElement,
): List<Any>? {
    val array = value as? JsonArray ?: return null
    return when (path) {
        "settings.tcp_chain_steps" -> array.mapOrNull { parseMessage(it, StrategyTcpStep::parseFrom) }
        "settings.udp_chain_steps" -> array.mapOrNull { parseMessage(it, StrategyUdpStep::parseFrom) }
        else -> array.mapOrNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}

private fun parseUnsupportedMessage(
    value: JsonElement,
    type: Class<*>,
): Any? {
    val bytes = base64Bytes(value)
    return if (MessageLite::class.java.isAssignableFrom(type) && bytes != null) {
        runCatching { type.getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes) }.getOrNull()
    } else {
        null
    }
}

private fun <T : MessageLite> parseMessage(
    value: JsonElement,
    parser: (ByteArray) -> T,
): T? {
    val bytes = base64Bytes(value) ?: return null
    return runCatching { parser(bytes) }.getOrNull()
}

private fun base64Bytes(value: JsonElement): ByteArray? {
    val encoded = (value as? JsonPrimitive)?.takeUnless { value is JsonNull }?.contentOrNull
    return encoded?.let { content ->
        runCatching {
            Base64
                .getUrlDecoder()
                .decode(
                    content.padEnd(
                        (content.length + Base64PaddingOffset) / Base64BlockSize * Base64BlockSize,
                        '=',
                    ),
                )
        }.getOrNull()
    }
}

private fun getter(
    messageClass: Class<*>,
    suffix: String,
): Method? =
    runCatching { messageClass.getMethod("get$suffix") }.getOrNull()
        ?: runCatching { messageClass.getMethod("get${suffix}List") }.getOrNull()

private fun displayValue(value: Any?): String =
    when (value) {
        null -> ""
        is MessageLite -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { displayValue(it) }
        else -> value.toString()
    }

private fun sensitivityFor(path: String): SupportSettingsSensitivity =
    if (path in sensitivePaths || sensitivePathFragments.any(path::contains)) {
        SupportSettingsSensitivity.Sensitive
    } else {
        SupportSettingsSensitivity.Standard
    }

private val sensitivePathFragments = setOf("token", "credential", "password", "private_key", "keylog")

private val sensitivePaths =
    setOf(
        "settings.root_mode_enabled",
        "settings.proxy_allow_lan",
        "settings.relay_dns_over_tunnel_enabled",
        "settings.community_comparison_enabled",
        "settings.detection_diagnostic_tls_keylog_path",
        "settings.ws_tunnel_allow_insecure_sni",
    )

private fun String.toSnakeCase(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .replace('-', '_')
        .lowercase()

private inline fun <T, R : Any> Iterable<T>.mapOrNull(transform: (T) -> R?): List<R>? {
    val result = mutableListOf<R>()
    for (item in this) {
        result += transform(item) ?: return null
    }
    return result
}
