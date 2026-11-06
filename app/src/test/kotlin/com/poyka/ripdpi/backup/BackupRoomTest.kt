package com.poyka.ripdpi.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import org.junit.After

abstract class BackupRoomTest {
    private val fixtures = mutableListOf<RoomProfileFixture>()

    protected suspend fun roomFixture(): RoomProfileFixture =
        RoomProfileFixture(ApplicationProvider.getApplicationContext<Context>(), testPauseAuthority())
            .also {
                fixtures += it
                it.initialize()
            }

    @After
    fun closeDatabases() = fixtures.forEach(RoomProfileFixture::close)

    protected suspend fun TestScope.awaitOperations() {
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }
}
