package com.orchords.orchordsai.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orchords.orchordsai.data.files.FilesManager
import com.orchords.orchordsai.data.repository.FilesRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trip test for [MessageNodePayloadStore] using a real [FilesManager] +
 * `ManagedFileDAO` in an in-memory Room DB. Verifies the 256 KiB threshold
 * logic plus file/row lifecycle.
 *
 * See issue #345.
 */
@RunWith(AndroidJUnit4::class)
class MessageNodePayloadStoreTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var filesManager: FilesManager
    private lateinit var store: MessageNodePayloadStore

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesManager = FilesManager(
            context = context,
            repository = FilesRepository(dao = database.managedFileDao()),
            appScope = com.orchords.orchordsai.AppScope(),
        )
        store = MessageNodePayloadStore(
            filesManager = filesManager,
            managedFileDao = database.managedFileDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun smallPayloadIsKeptInlineAndNoFileWritten() = runBlocking {
        val blobId = store.store(nodeId("small"), "[]")
        assertNull(blobId)
        assertEquals(0, database.managedFileDao().getAll().size)
    }

    @Test
    fun largePayloadIsExternalizedAndFileWritten() = runBlocking {
        val json = buildLargeJson(MessageNodePayloadStore.MAX_INLINE_BYTES + 1024)
        val blobId = store.store(nodeId("large"), json)
        assertNotNull(blobId)
        val all = database.managedFileDao().getAll()
        assertEquals(1, all.size)
        assertEquals(MessageNodePayloadStore.PAYLOAD_FOLDER, all[0].folder)
        assertTrue(all[0].relativePath.endsWith(".json"))
        assertTrue(all[0].sizeBytes >= MessageNodePayloadStore.MAX_INLINE_BYTES.toLong())
    }

    @Test
    fun loadRoundTripReturnsIdenticalJson() = runBlocking {
        val json = buildLargeJson(MessageNodePayloadStore.MAX_INLINE_BYTES + 4096)
        val blobId = store.store(nodeId("round-trip"), json)
        assertNotNull(blobId)
        assertEquals(json, store.load(blobId))
    }

    @Test
    fun loadOnNullBlobIdReturnsNull() = runBlocking {
        assertNull(store.load(null))
    }

    @Test
    fun loadOnMissingBlobIdReturnsNull() = runBlocking {
        assertNull(store.load(987654L))
    }

    @Test
    fun deleteOnNullBlobIdIsNoOp() = runBlocking {
        store.delete(null)
        assertEquals(0, database.managedFileDao().getAll().size)
    }

    @Test
    fun deleteRemovesFileAndRow() = runBlocking {
        val json = buildLargeJson(MessageNodePayloadStore.MAX_INLINE_BYTES + 4096)
        val blobId = store.store(nodeId("delete"), json)
        assertNotNull(blobId)
        val entityBefore = database.managedFileDao().getById(blobId!!)
        assertNotNull(entityBefore)
        val fileBefore = filesManager.getFileOrNull(entityBefore!!)
        assertNotNull(fileBefore)
        assertTrue(fileBefore!!.exists())

        store.delete(blobId)

        val entityAfter = database.managedFileDao().getById(blobId)
        assertNull(entityAfter)
        assertFalse(fileBefore.exists())
    }

    @Test
    fun boundaryAtExactThresholdIsInline() = runBlocking {
        val json = buildLargeJson(MessageNodePayloadStore.MAX_INLINE_BYTES)
        assertEquals(
            MessageNodePayloadStore.MAX_INLINE_BYTES.toLong(),
            json.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        assertNull(store.store(nodeId("at"), json))
    }

    @Test
    fun boundaryOneOverThresholdIsExternalized() = runBlocking {
        val json = buildLargeJson(MessageNodePayloadStore.MAX_INLINE_BYTES + 1)
        assertNotNull(store.store(nodeId("over"), json))
    }

    private fun nodeId(label: String): String {
        val hex = label.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return "00000000-0000-0000-0000-" + hex.padEnd(12, '0').take(12)
    }

    private fun buildLargeJson(size: Int): String {
        require(size > 4)
        val padding = "x".repeat(size - 2)
        return "[" + padding + "]"
    }
}
