package com.poyka.ripdpi.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupShareViewModelOwnershipTest {
    @Test
    fun `view model clears its share file when route scope ends`() {
        val source = source().readText()

        assertTrue(source.contains("internal val shareTempFiles = BackupShareTempFileOwner()"))
        assertTrue(source.contains("override fun onCleared()"))
        assertTrue(source.contains("shareTempFiles.close()"))
    }

    private fun source(): File =
        sequenceOf(
            File("src/main/kotlin/com/poyka/ripdpi/backup/BackupRestoreViewModel.kt"),
            File("app/src/main/kotlin/com/poyka/ripdpi/backup/BackupRestoreViewModel.kt"),
        ).first(File::isFile)
}
