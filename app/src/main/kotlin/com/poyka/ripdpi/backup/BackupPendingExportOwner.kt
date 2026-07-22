package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.backup.BackupVariant
import java.io.Closeable

/** In-memory handoff for a SAF export request. Secrets are never written to saved state. */
internal class PendingBackupExport(
    val variant: BackupVariant,
    val passphrase: CharArray?,
) : Closeable {
    override fun close() {
        passphrase?.fill('\u0000')
    }
}

/**
 * Retains one export request across Activity recreation through its owning ViewModel.
 * Replacing, cancelling, or clearing the ViewModel wipes the retained secret. A consumed request
 * transfers that responsibility to its caller through [PendingBackupExport.close].
 */
internal class BackupPendingExportOwner : Closeable {
    private var pending: PendingBackupExport? = null

    @Synchronized
    fun stage(
        variant: BackupVariant,
        passphrase: CharArray? = null,
    ) {
        clear()
        val ownedPassphrase = passphrase?.copyOf()
        passphrase?.fill('\u0000')
        pending = PendingBackupExport(variant, ownedPassphrase)
    }

    @Synchronized
    fun consume(): PendingBackupExport? = pending.also { pending = null }

    @Synchronized
    fun clear() {
        pending?.close()
        pending = null
    }

    override fun close() = clear()
}
