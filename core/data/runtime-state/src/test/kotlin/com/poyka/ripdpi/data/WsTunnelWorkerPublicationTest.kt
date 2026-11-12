package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

class WsTunnelWorkerPublicationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `same reference replacement has a coherent cold read after every durable write`() =
        runTest {
            val fixture = fixture()
            fixture.provisioner.provision(OldUrl, Ref, SecretString(OldBearer))
            val checkpoints = mutableListOf<File>()
            val checkpoint = {
                val directory = temporaryFolder.newFolder()
                fixture.directory.copyRecursively(directory, overwrite = true)
                checkpoints += directory
                Unit
            }
            fixture.backend.afterCommit = checkpoint
            fixture.settings.afterCommit = checkpoint

            val config = fixture.provisioner.provision(NewUrl, Ref, SecretString(NewBearer))

            assertNotEquals(Ref, config.credentialRef)
            assertEquals(
                listOf(OldUrl to OldBearer, NewUrl to NewBearer, NewUrl to NewBearer),
                checkpoints.map { directory ->
                    val cold = WorkerTransportDiskFixture(directory)
                    val transport = cold.store.resolveTransport(cold.settings.snapshot())
                    transport?.workerUrl to transport?.authBearer?.value
                },
            )
            assertNull(fixture.store.load(Ref))
        }

    @Test
    fun `clear has no configured route after either durable write`() =
        runTest {
            val fixture = fixture()
            fixture.provisioner.provision(OldUrl, Ref, SecretString(OldBearer))
            val checkpoints = mutableListOf<File>()
            val checkpoint = {
                val directory = temporaryFolder.newFolder()
                fixture.directory.copyRecursively(directory, overwrite = true)
                checkpoints += directory
                Unit
            }
            fixture.backend.afterCommit = checkpoint
            fixture.settings.afterCommit = checkpoint

            fixture.provisioner.clear()

            assertEquals(2, checkpoints.size)
            checkpoints.forEach { directory ->
                val cold = WorkerTransportDiskFixture(directory)
                assertNull(cold.store.resolveTransport(cold.settings.snapshot()))
            }
        }

    @Test
    fun `caller cancellation after credential IO still publishes the complete replacement`() =
        runTest {
            val fixture = fixture()
            fixture.provisioner.provision(OldUrl, Ref, SecretString(OldBearer))
            lateinit var saving: Job
            fixture.backend.afterCommit = { saving.cancel() }
            saving =
                launch(start = CoroutineStart.LAZY) {
                    fixture.provisioner.provision(NewUrl, Ref, SecretString(NewBearer))
                }

            saving.start()
            saving.join()

            assertTrue(saving.isCancelled)
            val cold = WorkerTransportDiskFixture(fixture.directory)
            val transport = cold.store.resolveTransport(cold.settings.snapshot())
            assertEquals(NewUrl, transport?.workerUrl)
            assertEquals(NewBearer, transport?.authBearer?.value)
        }

    @Test
    fun `caller cancellation after credential removal leaves no published route`() =
        runTest {
            val fixture = fixture()
            fixture.provisioner.provision(OldUrl, Ref, SecretString(OldBearer))
            lateinit var clearing: Job
            fixture.backend.afterCommit = { clearing.cancel() }
            clearing = launch(start = CoroutineStart.LAZY) { fixture.provisioner.clear() }

            clearing.start()
            clearing.join()

            assertTrue(clearing.isCancelled)
            val cold = WorkerTransportDiskFixture(fixture.directory)
            assertNull(cold.store.resolveTransport(cold.settings.snapshot()))
        }

    private fun fixture() = WorkerTransportDiskFixture(temporaryFolder.newFolder())

    private companion object {
        const val OldUrl = "https://old.example/ws"
        const val NewUrl = "https://new.example/ws"
        const val Ref = "worker-main"
        const val OldBearer = "old-test-bearer"
        const val NewBearer = "new-test-bearer"
    }
}

// Uses the production IO adapter with fsynced test storage; AndroidKeyStore and LMK need device acceptance.
private class WorkerTransportDiskFixture(
    val directory: File,
) {
    val settings = DiskWorkerSettings(File(directory, "settings.pb"))
    val backend = DiskWorkerCredentialBackend(File(directory, "credentials.properties"))
    val store = KeystoreWsTunnelWorkerCredentialStore(backend)
    val provisioner = WsTunnelWorkerTransportProvisioner(settings, store)
}

private class DiskWorkerSettings(
    private val file: File,
) : AppSettingsRepository {
    var afterCommit: () -> Unit = {}
    override val settings = flow { emit(snapshot()) }

    override suspend fun snapshot(): AppSettings =
        withContext(Dispatchers.IO) {
            if (file.exists()) AppSettings.parseFrom(file.readBytes()) else AppSettingsSerializer.defaultValue
        }

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        replace(snapshot().toBuilder().apply(transform).build())
    }

    override suspend fun replace(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            writeWorkerFixtureFile(file) { it.write(settings.toByteArray()) }
            afterCommit()
        }
    }
}

private class DiskWorkerCredentialBackend(
    private val file: File,
) : WsTunnelWorkerCredentialBackend {
    var afterCommit: () -> Unit = {}

    override fun getStringStrict(key: String): String? = read().getProperty(key)

    override fun putString(
        key: String,
        value: String,
    ) = write(read().apply { setProperty(key, value) })

    override fun remove(key: String) = write(read().apply { remove(key) })

    override fun clear() = write(Properties())

    private fun read() = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

    private fun write(properties: Properties) {
        writeWorkerFixtureFile(file) { properties.store(it, null) }
        afterCommit()
    }
}

private fun writeWorkerFixtureFile(
    file: File,
    write: (FileOutputStream) -> Unit,
) {
    FileOutputStream(file).use { output ->
        write(output)
        output.fd.sync()
    }
}
