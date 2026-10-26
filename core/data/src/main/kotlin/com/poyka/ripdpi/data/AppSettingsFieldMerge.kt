package com.poyka.ripdpi.data

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.poyka.ripdpi.proto.AppSettings

/**
 * Three-way merge of top-level [AppSettings] fields: every field whose encoding differs between
 * [base] and [after] takes its [after] value (or is cleared), every other field keeps [current].
 *
 * An editor builds [after] from the [base] snapshot it read earlier. Writing [after] whole would
 * revert any setting another writer changed since then. Repeated and nested fields merge as one
 * unit, so a conflicting change to the same field resolves to [after].
 */
internal fun mergeChangedSettingsFields(
    base: AppSettings,
    after: AppSettings,
    current: AppSettings,
): AppSettings {
    val baseFields = topLevelFields(base.toByteString())
    val afterFields = topLevelFields(after.toByteString())
    val currentFields = topLevelFields(current.toByteString())
    val merged =
        (baseFields.keys + afterFields.keys + currentFields.keys)
            .sorted()
            .mapNotNull { field ->
                if (baseFields[field] != afterFields[field]) afterFields[field] else currentFields[field]
            }
    return AppSettings.parseFrom(ByteString.copyFrom(merged))
}

/** Raw tag-and-value bytes of each top-level field, all occurrences concatenated in wire order. */
private fun topLevelFields(bytes: ByteString): Map<Int, ByteString> {
    val fields = LinkedHashMap<Int, ByteString>()
    val input = CodedInputStream.newInstance(bytes.toByteArray())
    while (true) {
        val start = input.totalBytesRead
        val tag = input.readTag()
        if (tag == 0) break
        input.skipField(tag)
        val field = tag ushr TagTypeBits
        val occurrence = bytes.substring(start, input.totalBytesRead)
        fields[field] = fields[field]?.concat(occurrence) ?: occurrence
    }
    return fields
}

private const val TagTypeBits = 3
