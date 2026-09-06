package com.orchords.orchordsai.ui.startup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPresentationPolicyTest {
    private fun activitySource(): String {
        val appModule = File(".").canonicalFile
        val activity = appModule.resolve(
            "src/main/java/com/orchords/orchordsai/OrchordsAiActivity.kt"
        )
        require(activity.isFile) { "Expected OrchordsAiActivity.kt at ${activity.path}" }
        return activity.readText()
    }

    @Test
    fun `activity owns exactly one startup loading indicator host`() {
        val activityBody = activitySource().substringAfter("class OrchordsAiActivity")
        val renderedHosts = activityBody.split("OrchardsStartupLoadingIndicator(").size - 1
        assertEquals(
            "Startup and migration readiness must share one rendered full-screen loader host",
            1,
            renderedHosts,
        )
    }

    @Test
    fun `app routes do not own a second migration blocker`() {
        val appRoutes = activitySource().substringAfter("fun AppRoutes()")
        assertFalse(
            "AppRoutes must not render an independent migration full-screen blocker",
            appRoutes.contains("visible = migrationState is MigrationState.Migrating"),
        )
    }

    @Test
    fun `startup completion remains guarded by migration readiness`() {
        val source = activitySource()
        assertTrue(source.contains("visible = showStartup || migrationState is MigrationState.Migrating"))
        assertTrue(source.contains("if (migrationState !is MigrationState.Migrating)"))
    }
}
