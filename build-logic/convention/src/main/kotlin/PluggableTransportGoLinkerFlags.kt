fun pluggableTransportGoLinkerFlags(record: Map<*, *>): List<String> {
    val value = record["goLinkerFlags"] ?: return emptyList()
    require(value is List<*> && value.all { it is String && it.isNotBlank() && it.none(Char::isWhitespace) }) {
        "Pluggable transport goLinkerFlags must contain nonempty single-token strings"
    }
    return value.map { it as String }
}

fun verifyPluggableTransportGoLinkerFlags(
    record: Map<*, *>,
    expected: List<String>,
    sourceId: String,
) {
    require(pluggableTransportGoLinkerFlags(record) == expected) {
        "Prebuilt pluggable transport source $sourceId has unexpected Go linker flags"
    }
}

fun pluggableTransportGoLinkerArgument(flags: List<String>): String =
    (listOf("-s", "-w", "-buildid=") + flags).joinToString(" ", prefix = "-ldflags=")
