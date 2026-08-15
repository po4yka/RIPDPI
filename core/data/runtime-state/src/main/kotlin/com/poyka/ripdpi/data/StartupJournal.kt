package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

private const val DefaultStartupJournalBytes = 32 * 1024
internal const val StartupJournalTruncationMarker = "[startup journal truncated: newest entries retained]\n"

data class StartupJournalSnapshot(
    val content: String,
    val byteCount: Int,
    val truncated: Boolean,
)

interface StartupJournal {
    fun recordServiceStarted(
        mode: Mode,
        occurredAtMillis: Long,
    )

    fun snapshot(): StartupJournalSnapshot
}

object NoopStartupJournal : StartupJournal {
    override fun recordServiceStarted(
        mode: Mode,
        occurredAtMillis: Long,
    ) = Unit

    override fun snapshot(): StartupJournalSnapshot =
        StartupJournalSnapshot(content = "", byteCount = 0, truncated = false)
}

@Singleton
class DefaultStartupJournal
    @Inject
    constructor() : StartupJournal {
        private val delegate = BoundedStartupJournal(DefaultStartupJournalBytes)

        override fun recordServiceStarted(
            mode: Mode,
            occurredAtMillis: Long,
        ) = delegate.recordServiceStarted(mode, occurredAtMillis)

        override fun snapshot(): StartupJournalSnapshot = delegate.snapshot()
    }

internal class BoundedStartupJournal(
    private val maxBytes: Int,
) : StartupJournal {
    private val entries = ArrayDeque<ByteArray>()
    private var entriesByteCount = 0
    private var wasTruncated = false

    init {
        require(maxBytes > StartupJournalTruncationMarker.toByteArray(Charsets.UTF_8).size) {
            "Startup journal capacity must fit its truncation marker"
        }
    }

    @Synchronized
    override fun recordServiceStarted(
        mode: Mode,
        occurredAtMillis: Long,
    ) {
        val entry = "$occurredAtMillis service_started mode=${mode.name.lowercase()}\n".toByteArray(Charsets.UTF_8)
        val markerBytes = StartupJournalTruncationMarker.toByteArray(Charsets.UTF_8)
        while (entries.isNotEmpty() && entriesByteCount + entry.size + markerBytes.size > maxBytes) {
            entriesByteCount -= entries.removeFirst().size
            wasTruncated = true
        }
        if (entry.size + markerBytes.size > maxBytes) {
            wasTruncated = true
            return
        }
        entries.addLast(entry)
        entriesByteCount += entry.size
    }

    @Synchronized
    override fun snapshot(): StartupJournalSnapshot {
        val marker = if (wasTruncated) StartupJournalTruncationMarker.toByteArray(Charsets.UTF_8) else byteArrayOf()
        val bytes = marker + entries.fold(byteArrayOf()) { accumulated, entry -> accumulated + entry }
        return StartupJournalSnapshot(
            content = bytes.toString(Charsets.UTF_8),
            byteCount = bytes.size,
            truncated = wasTruncated,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StartupJournalModule {
    @Binds
    @Singleton
    abstract fun bindStartupJournal(journal: DefaultStartupJournal): StartupJournal
}
