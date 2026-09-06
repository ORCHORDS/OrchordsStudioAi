package com.orchords.orchordsai.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orchords.orchordsai.data.db.AppDatabase
import com.orchords.orchordsai.data.db.entity.ConversationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the schema upgrade from `message_node` schema v24 to v25.
 *
 * Pre-v25 rows store their message JSON inline; v25 adds the optional
 * `payload_blob_id` column (defaulting to NULL) and an index. No rows are
 * eagerly backfilled — oversized rows stay inline and get externalized
 * lazily on next write through `MessageNodePayloadStore` (see issue #345).
 */
@RunWith(AndroidJUnit4::class)
class Migration_24_25_Test {
    private val TEST_DB = "migration-24-25-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate24To25_addsPayloadBlobIdColumnWithDefaultNull() {
        helper.createDatabase(TEST_DB, 24).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, Migration_24_25)

        val cursor = db.query("SELECT * FROM message_node LIMIT 0")
        val columns = cursor.columnNames.toList()
        cursor.close()

        assertTrue("payload_blob_id column should exist", columns.contains("payload_blob_id"))

        // Insert a row without specifying payload_blob_id; expect NULL (the column default).
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('m', 'c', 0, '[]', 0)"
        )

        val check = db.query("SELECT payload_blob_id FROM message_node WHERE id = 'm'")
        assertTrue("Inserted row should exist", check.moveToFirst())
        assertTrue("payload_blob_id default is NULL", check.isNull(0))
        check.close()

        db.close()
    }

    @Test
    fun migrate24To25_createsIndexOnPayloadBlobId() {
        helper.createDatabase(TEST_DB, 24).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, Migration_24_25)

        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' " +
                "AND tbl_name='message_node' AND name='index_message_node_payload_blob_id'"
        )
        assertTrue("index_message_node_payload_blob_id should exist", cursor.count > 0)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate24To25_preservesExistingInlineMessagesAndConversations() {
        val conversationId = "00000000-0000-0000-0000-000000000255"
        val nodeId1 = "00000000-0000-0000-0000-000000000001"
        val nodeId2 = "00000000-0000-0000-0000-000000000002"
        val legacyInline1 = """[{"role":"user","parts":[{"text":"hi"}]}]"""
        val legacyInline2 = """[{"role":"assistant","parts":[{"text":"hello"}]}]"""

        helper.createDatabase(TEST_DB, 24).apply {
            // Use the actual v24 ConversationEntity schema; truncate_index was removed before v24.
            execSQL(
                "INSERT INTO conversationentity (id, assistant_id, title, nodes, suggestions, is_pinned, create_at, update_at) " +
                    "VALUES ('$conversationId', 'assistant-id', 'legacy', '[]', '[]', 0, 0, 0)"
            )
            execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                    "VALUES ('$nodeId1', '$conversationId', 0, '$legacyInline1', 0)"
            )
            execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                    "VALUES ('$nodeId2', '$conversationId', 1, '$legacyInline2', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, Migration_24_25)

        db.assertLegacyRowUnchanged(nodeId1, conversationId, 0, legacyInline1)
        db.assertLegacyRowUnchanged(nodeId2, conversationId, 1, legacyInline2)
        // payload_blob_id defaults to NULL on legacy rows.
        val blobCheck = db.query(
            "SELECT payload_blob_id FROM message_node WHERE id = '$nodeId1'"
        )
        assertTrue(blobCheck.moveToFirst())
        assertTrue("Legacy inline row keeps payload_blob_id NULL", blobCheck.isNull(0))
        blobCheck.close()

        db.close()
    }

    private fun SupportSQLiteDatabase.assertLegacyRowUnchanged(
        nodeId: String,
        conversationId: String,
        nodeIndex: Int,
        expectedMessages: String,
    ) {
        val cursor = query(
            "SELECT id, conversation_id, node_index, messages, select_index FROM message_node WHERE id = ?",
            arrayOf(nodeId)
        )
        assertTrue("Legacy row $nodeId should survive migration", cursor.moveToFirst())
        assertEquals(nodeId, cursor.getString(0))
        assertEquals(conversationId, cursor.getString(1))
        assertEquals(nodeIndex, cursor.getInt(2))
        assertEquals(expectedMessages, cursor.getString(3))
        assertEquals(0, cursor.getInt(4))
        cursor.close()
    }

    @Test
    fun migrate24To25_supportsSettingPayloadBlobId() {
        helper.createDatabase(TEST_DB, 24).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, Migration_24_25)

        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index, payload_blob_id) " +
                "VALUES ('m-blob', 'c', 0, '', 0, 99)"
        )

        val cursor = db.query("SELECT payload_blob_id FROM message_node WHERE id = 'm-blob'")
        assertTrue(cursor.moveToFirst())
        assertEquals(99L, cursor.getLong(0))
        cursor.close()
        db.close()
    }

    @Test
    fun migrate24To25_exposesColumnThroughRoomAtV25() = runBlocking {
        helper.createDatabase(TEST_DB, 24).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 25, true, Migration_24_25).close()

        // Open a Room-backed database at the new schema and verify the column
        // is wired up to the entity.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.databaseBuilder(
            ctx,
            AppDatabase::class.java,
            "${TEST_DB}-room"
        )
            .addMigrations(Migration_24_25)
            .build()
        try {
            // MessageNodeEntity has a real FK to ConversationEntity, so create its parent first.
            val conversationId = "00000000-0000-0000-0000-000000000020"
            db.conversationDao().insert(
                ConversationEntity(
                    id = conversationId,
                    assistantId = "assistant-id",
                    title = "migration parent",
                    nodes = "[]",
                    createAt = 0L,
                    updateAt = 0L,
                    chatSuggestions = "[]",
                    isPinned = false,
                )
            )
            val node = com.orchords.orchordsai.data.db.entity.MessageNodeEntity(
                id = "00000000-0000-0000-0000-000000000010",
                conversationId = conversationId,
                nodeIndex = 0,
                messages = "[]",
                selectIndex = 0,
                payloadBlobId = null,
            )
            db.messageNodeDao().insert(node)
            val roundTripped = db.messageNodeDao().getNodeById(node.id)
            assertNotNull(roundTripped)
            assertNull(roundTripped?.payloadBlobId)
        } finally {
            db.close()
        }
    }
}
