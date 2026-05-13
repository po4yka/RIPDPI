package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val StrategyConfigMaxImportBytes = 64 * 1024

internal sealed class StrategyConfigImportException(
    message: String,
) : Exception(message) {
    data object FileTooLarge : StrategyConfigImportException("file_too_large")

    data object EmptyFile : StrategyConfigImportException("empty_file")

    data object UnreadableFile : StrategyConfigImportException("unreadable_file")

    data object InvalidUtf8 : StrategyConfigImportException("invalid_utf8")
}

internal fun readStrategyConfigText(
    context: Context,
    uri: Uri,
    maxBytes: Int = StrategyConfigMaxImportBytes,
): Result<String> =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            readLimitedUtf8Text(input, maxBytes)
        } ?: throw StrategyConfigImportException.UnreadableFile
    }

internal fun readLimitedUtf8Text(
    input: InputStream,
    maxBytes: Int = StrategyConfigMaxImportBytes,
): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0

    while (true) {
        val read = input.read(buffer)
        if (read == -1) {
            break
        }
        total += read
        if (total > maxBytes) {
            throw StrategyConfigImportException.FileTooLarge
        }
        output.write(buffer, 0, read)
    }

    val text = output.toByteArray().decodeStrictUtf8()
    if (text.isBlank()) {
        throw StrategyConfigImportException.EmptyFile
    }
    return text
}

private fun ByteArray.decodeStrictUtf8(): String =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        throw StrategyConfigImportException.InvalidUtf8
    }
