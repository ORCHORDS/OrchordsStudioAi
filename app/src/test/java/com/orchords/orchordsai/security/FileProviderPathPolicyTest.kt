package com.orchords.orchordsai.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileProviderPathPolicyTest {
    private fun moduleFile(relative: String): File {
        val moduleDir = File(".").canonicalFile
        require(moduleDir.resolve("src/main/res").isDirectory) {
            "Unexpected working directory ${moduleDir.path}: unit tests must run from the app module"
        }
        val file = moduleDir.resolve(relative).canonicalFile
        require(file.toPath().startsWith(moduleDir.toPath())) { "Path escapes app module: $relative" }
        return file
    }

    private fun source(relative: String): String = moduleFile(relative).readText()

    private fun assertRoot(xml: String, element: String, name: String, path: String) {
        val compact = xml.replace(Regex("\\s+"), " ")
        val pattern = Regex("<$element\\s+name=\\\"${Regex.escape(name)}\\\"\\s+path=\\\"${Regex.escape(path)}\\\"\\s*/>")
        assertTrue("$element '$name' must map only '$path'", pattern.containsMatchIn(compact))
    }

    @Test
    fun `file provider exposes only explicit share roots`() {
        val xml = source("src/main/res/xml/file_paths.xml")

        assertFalse(
            "FileProvider roots must never expose an entire storage domain with path=dot",
            Regex("path\\s*=\\s*\\\"\\.\\\"").containsMatchIn(xml),
        )
        assertFalse(
            "No current FileProvider callsite requires external-files authority",
            xml.contains("<external-files-path"),
        )

        assertRoot(xml, "files-path", "upload", "upload/")
        assertRoot(xml, "cache-path", "camera", "camera/")
        assertRoot(xml, "cache-path", "temp", "temp/")
        assertRoot(xml, "cache-path", "export", "export/")
        assertRoot(xml, "cache-path", "workspace_share", "workspace_share/")

        assertFalse("tool outputs must not be grantable", xml.contains("tool_outputs"))
        assertFalse("skills must not be grantable", xml.contains("skills"))
    }

    @Test
    fun `camera capture files are staged inside the dedicated camera root`() {
        val shortcut = source("src/main/java/com/orchords/orchordsai/ui/activity/ShortcutHandlerActivity.kt")
        val picker = source("src/main/java/com/orchords/orchordsai/ui/components/ai/ChatAttachmentPicker.kt")

        assertTrue(
            "Shortcut camera capture must use cacheDir/camera",
            shortcut.contains("File(cacheDir, \"camera\")"),
        )
        assertTrue(
            "Chat camera capture must use cacheDir/camera",
            picker.contains("File(context.cacheDir, \"camera\")"),
        )
    }

    @Test
    fun `existing share flows use allowlisted cache namespaces`() {
        val exportHooks = source("src/main/java/com/orchords/orchordsai/data/export/ExportHooks.kt")
        val chatExport = source("src/main/java/com/orchords/orchordsai/ui/pages/chat/Export.kt")
        val workspaceVm = source("src/main/java/com/orchords/orchordsai/ui/pages/extensions/workspace/WorkspaceDetailVM.kt")
        val editedFiles = source("src/main/java/com/orchords/orchordsai/ui/components/message/ChatMessageEditedFiles.kt")

        assertTrue(exportHooks.contains("File(context.cacheDir, \"export\")"))
        assertTrue(chatExport.contains("context.appTempFolder"))
        assertTrue(workspaceVm.contains("File(cacheDir, \"workspace_share\")"))
        assertTrue(editedFiles.contains("File(context.cacheDir, \"workspace_share\")"))
    }

    @Test
    fun `share flows never grant write access`() {
        val files = listOf(
            "src/main/java/com/orchords/orchordsai/ui/activity/ShortcutHandlerActivity.kt",
            "src/main/java/com/orchords/orchordsai/ui/components/ai/ChatAttachmentPicker.kt",
            "src/main/java/com/orchords/orchordsai/data/export/ExportHooks.kt",
            "src/main/java/com/orchords/orchordsai/ui/pages/chat/Export.kt",
            "src/main/java/com/orchords/orchordsai/ui/components/message/ChatMessage.kt",
            "src/main/java/com/orchords/orchordsai/ui/components/message/ChatMessageEditedFiles.kt",
            "src/main/java/com/orchords/orchordsai/ui/pages/extensions/workspace/WorkspaceDetailPage.kt",
        )
        val combined = files.joinToString("\n") { source(it) }
        assertFalse(
            "Read-only share/capture flows must not grant write URI permission",
            combined.contains("FLAG_GRANT_WRITE_URI_PERMISSION"),
        )
    }
}
