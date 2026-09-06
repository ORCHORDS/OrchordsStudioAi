package com.orchords.orchordsai.ui.startup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StartupPresentationPolicyTest {
    private fun activitySource(): String {
        val appModule = File(".").canonicalFile
        val activity = appModule.resolve(
            "src/main/java/com/orchords/orchordsai/OrchordsAiActivity.kt"
        )
        require(activity.isFile) {
            "Expected OrchordsAiActivity.kt at ${activity.path}"
        }
        return activity.readText()
    }

    @Test
    fun `activity owns exactly one startup loading indicator host`() {
        val activityBody = activitySource().substringAfter("class OrchordsAiActivity")
        val renderedHosts = activityBody.split("OrchordsStartupLoadingIndicator(").size - 1

        assertEquals(
            "Startup and migration readiness must share one rendered full-screen loader host; found $renderedHosts",
            1,
            renderedHosts,
        )
    }

    @Test
    fun `app routes do not own a second migration blocker`() {
        val source = activitySource()
        val appRoutes = source.substringAfter("fun AppRoutes()")

        assertFalse(
            "AppRoutes must not render an independent migration full-screen blocker",
            appRoutes.contains("visible = migrationState is MigrationState.Migrating"),
        )
    }
}
