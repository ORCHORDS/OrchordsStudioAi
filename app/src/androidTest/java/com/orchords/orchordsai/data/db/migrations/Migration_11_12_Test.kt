package com.orchords.orchordsai.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orchords.ai.core.MessageRole
import com.orchords.ai.core.TokenUsage
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart
import com.orchords.orchordsai.data.db.AppDatabase
import com.orchords.orchordsai.data.model.MessageNode
import com.orchords.orchordsai.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_11_12_Test {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate11To12_createsMessageNodeTableWithCorrectSchema() {
        helper.createDatabase(TEST_DB, 11).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        val cursor = db.query("SELECT * FROM message_node LIMIT 0")
        val columnNames = cursor.columnNames.toList()
        cursor.close()

        assertTrue("message_node table should exist", columnNames.isNotEmpty())
        assertTrue("Should have 'id' column", columnNames.contains("id"))
        assertTrue("Should have 'conversation_id' column", columnNames.contains("conversation_id"))
        assertTrue("Should have 'node_index' column", columnNames.contains("node_index"))
        assertTrue("Should have 'messages' column", columnNames.contains("messages"))
        assertTrue("Should have 'select_index' column", columnNames.contains("select_index"))

        db.close()
    }

    @Test
    fun migrate11To12_migratesSimpleConversationCorrectly() {
        val conversationId = Uuid.random().toString()
        val messageNodes = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Hello"))
                    )
                ),
                selectIndex = 0
            ),
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Hi there!")),
                        modelId = Uuid.random(),
                        usage = TokenUsage(promptTokens = 10, completionTokens = 5)
                    )
                ),
                selectIndex = 0
            )
        )
        val nodesJson = JsonInstant.encodeToString(messageNodes)

        helper.createDatabase(TEST_DB, 11).apply {
            val values = ContentValues().apply {
                put("id", conversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Test Conversation")
                put("nodes", nodesJson)
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        val cursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
            arrayOf(conversationId)
        )

        assertEquals("Should have migrated 2 message nodes", 2, cursor.count)

        assertTrue(cursor.moveToFirst())
        val firstNodeId = cursor.getString(cursor.getColumnIndex("id"))
        val firstConversationId = cursor.getString(cursor.getColumnIndex("conversation_id"))
        val firstNodeIndex = cursor.getInt(cursor.getColumnIndex("node_index"))
        val firstMessagesJson = cursor.getString(cursor.getColumnIndex("messages"))
        val firstSelectIndex = cursor.getInt(cursor.getColumnIndex("select_index"))

        assertNotNull("First node should have ID", firstNodeId)
        assertEquals("Conversation ID should match", conversationId, firstConversationId)
        assertEquals("First node index should be 0", 0, firstNodeIndex)
        assertEquals("First node selectIndex should be 0", 0, firstSelectIndex)

        val firstMessages = JsonInstant.decodeFromString<List<UIMessage>>(firstMessagesJson)
        assertEquals("First node should have 1 message", 1, firstMessages.size)
        assertEquals("First message should be from USER", MessageRole.USER, firstMessages[0].role)
        assertEquals(
            "First message content should match",
            "Hello",
            (firstMessages[0].parts[0] as UIMessagePart.Text).text
        )

        assertTrue(cursor.moveToNext())
        val secondNodeIndex = cursor.getInt(cursor.getColumnIndex("node_index"))
        val secondMessagesJson = cursor.getString(cursor.getColumnIndex("messages"))

        assertEquals("Second node index should be 1", 1, secondNodeIndex)

        val secondMessages = JsonInstant.decodeFromString<List<UIMessage>>(secondMessagesJson)
        assertEquals("Second node should have 1 message", 1, secondMessages.size)
        assertEquals(
            "Second message should be from ASSISTANT",
            MessageRole.ASSISTANT,
            secondMessages[0].role
        )
        assertEquals(
            "Second message content should match",
            "Hi there!",
            (secondMessages[0].parts[0] as UIMessagePart.Text).text
        )

        cursor.close()

        val conversationCursor = db.query(
            "SELECT nodes FROM conversationentity WHERE id = ?",
            arrayOf(conversationId)
        )
        assertTrue(conversationCursor.moveToFirst())
        val updatedNodes = conversationCursor.getString(0)
        assertEquals("Original nodes should be cleared to empty array", "[]", updatedNodes)
        conversationCursor.close()

        db.close()
    }

    @Test
    fun migrate11To12_handlesBranchedMessages() {
        val conversationId = Uuid.random().toString()
        val messageNodes = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Response 1")),
                        modelId = Uuid.random()
                    ),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Response 2")),
                        modelId = Uuid.random()
                    ),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Response 3")),
                        modelId = Uuid.random()
                    )
                ),
                selectIndex = 1
            )
        )
        val nodesJson = JsonInstant.encodeToString(messageNodes)

        helper.createDatabase(TEST_DB, 11).apply {
            val values = ContentValues().apply {
                put("id", conversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Branched Conversation")
                put("nodes", nodesJson)
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        val cursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId)
        )

        assertEquals("Should have migrated 1 message node", 1, cursor.count)
        assertTrue(cursor.moveToFirst())

        val messagesJson = cursor.getString(cursor.getColumnIndex("messages"))
        val selectIndex = cursor.getInt(cursor.getColumnIndex("select_index"))

        val messages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
        assertEquals("Node should have 3 messages", 3, messages.size)
        assertEquals("selectIndex should be preserved", 1, selectIndex)
        assertEquals(
            "Should preserve all message variants",
            "Response 2",
            (messages[1].parts[0] as UIMessagePart.Text).text
        )

        cursor.close()
        db.close()
    }

    @Test
    fun migrate11To12_handlesEmptyConversations() {
        val conversationId = Uuid.random().toString()
        val nodesJson = "[]"

        helper.createDatabase(TEST_DB, 11).apply {
            val values = ContentValues().apply {
                put("id", conversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Empty Conversation")
                put("nodes", nodesJson)
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        val cursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId)
        )

        assertEquals("Empty conversation should have no message nodes", 0, cursor.count)
        cursor.close()

        val conversationCursor = db.query(
            "SELECT id FROM conversationentity WHERE id = ?",
            arrayOf(conversationId)
        )
        assertEquals("Conversation should still exist", 1, conversationCursor.count)
        conversationCursor.close()

        db.close()
    }

    @Test
    fun migrate11To12_handlesMultipleConversations() {
        val conversationId1 = Uuid.random().toString()
        val conversationId2 = Uuid.random().toString()

        val nodes1 = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Conversation 1"))
                    )
                ),
                selectIndex = 0
            )
        )

        val nodes2 = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Conversation 2 - Message 1"))
                    )
                ),
                selectIndex = 0
            ),
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Conversation 2 - Message 2"))
                    )
                ),
                selectIndex = 0
            )
        )

        helper.createDatabase(TEST_DB, 11).apply {
            val values1 = ContentValues().apply {
                put("id", conversationId1)
                put("assistant_id", Uuid.random().toString())
                put("title", "Conversation 1")
                put("nodes", JsonInstant.encodeToString(nodes1))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values1)

            val values2 = ContentValues().apply {
                put("id", conversationId2)
                put("assistant_id", Uuid.random().toString())
                put("title", "Conversation 2")
                put("nodes", JsonInstant.encodeToString(nodes2))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values2)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        val cursor1 = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId1)
        )
        assertEquals("Conversation 1 should have 1 message node", 1, cursor1.count)
        cursor1.close()

        val cursor2 = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId2)
        )
        assertEquals("Conversation 2 should have 2 message nodes", 2, cursor2.count)
        cursor2.close()

        val cursorAll = db.query("SELECT * FROM message_node")
        assertEquals("Total should have 3 message nodes", 3, cursorAll.count)
        cursorAll.close()

        db.close()
    }

    @Test
    fun migrate11To12_createsIndexOnConversationId() {
        helper.createDatabase(TEST_DB, 11).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='message_node' AND name='index_message_node_conversation_id'"
        )

        assertTrue("Index on conversation_id should exist", cursor.count > 0)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate11To12_handlesVeryLargeConversations() {
        val largeConversationId = Uuid.random().toString()
        val normalConversationId = Uuid.random().toString()

        val largeNodes = buildList {
            repeat(5000) { i ->
                add(
                    MessageNode(
                        id = Uuid.random(),
                        messages = listOf(
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(
                                    UIMessagePart.Text(
                                        "Message $i with some content to increase size " + "x".repeat(
                                            100
                                        )
                                    )
                                )
                            ),
                            UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Text(
                                        "Response $i with some content to increase size " + "y".repeat(
                                            100
                                        )
                                    )
                                ),
                                modelId = Uuid.random()
                            )
                        ),
                        selectIndex = 0
                    )
                )
            }
        }

        val normalNodes = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Normal conversation message"))
                    )
                ),
                selectIndex = 0
            )
        )

        helper.createDatabase(TEST_DB, 11).apply {
            val largeValues = ContentValues().apply {
                put("id", largeConversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Very Large Conversation")
                put("nodes", JsonInstant.encodeToString(largeNodes))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, largeValues)

            val normalValues = ContentValues().apply {
                put("id", normalConversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Normal Conversation")
                put("nodes", JsonInstant.encodeToString(normalNodes))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, normalValues)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        val largeCursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(largeConversationId)
        )
        val largeNodesMigrated = largeCursor.count
        largeCursor.close()

        val normalCursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(normalConversationId)
        )
        assertEquals("Normal conversation should be migrated successfully", 1, normalCursor.count)
        normalCursor.close()

        val conversationsCursor = db.query("SELECT id FROM conversationentity")
        assertEquals("Both conversations should still exist", 2, conversationsCursor.count)
        conversationsCursor.close()

        // Do not materialize the multi-megabyte legacy nodes value into Android's CursorWindow.
        // The migration deliberately preserves that value when it cannot be safely migrated;
        // length(nodes) proves whether it was cleared or retained without reintroducing the
        // CursorWindow failure that this test is meant to guard against.
        val largeConvCursor = db.query(
            "SELECT length(nodes) FROM conversationentity WHERE id = ?",
            arrayOf(largeConversationId)
        )
        assertTrue(largeConvCursor.moveToFirst())
        val largeConvNodesLength = largeConvCursor.getLong(0)
        largeConvCursor.close()

        val normalConvCursor = db.query(
            "SELECT nodes FROM conversationentity WHERE id = ?",
            arrayOf(normalConversationId)
        )
        assertTrue(normalConvCursor.moveToFirst())
        val normalConvNodes = normalConvCursor.getString(0)
        assertEquals("Normal conversation nodes should be cleared", "[]", normalConvNodes)
        normalConvCursor.close()

        Log.i(
            "Migration_11_12_Test",
            "Large conversation migration result: $largeNodesMigrated nodes migrated, nodes field: ${if (largeConvNodesLength == 2L) "cleared" else "preserved"}"
        )

        db.close()
    }
}
