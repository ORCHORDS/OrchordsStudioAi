package com.orchords.orchordsai.security

import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.orchords.orchordsai.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File

class InstalledPackageSecurityPolicyTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val backupDomains = setOf("root", "file", "database", "sharedpref", "external")

    @Test
    fun packagedAndroid12BackupRulesFailClosedForCloudAndDeviceTransfer() {
        val sections = sectionExcludes(R.xml.data_extraction_rules)

        assertEquals(setOf("cloud-backup", "device-transfer"), sections.keys)
        assertEquals(backupDomains.map { it to "." }.toSet(), sections.getValue("cloud-backup"))
        assertEquals(backupDomains.map { it to "." }.toSet(), sections.getValue("device-transfer"))
    }

    @Test
    fun packagedLegacyBackupRulesFailClosedForEveryBackupDomain() {
        val parser = context.resources.getXml(R.xml.backup_rules)
        val excludes = mutableSetOf<Pair<String, String>>()
        var includeCount = 0
        parser.use {
            while (it.eventType != XmlPullParser.END_DOCUMENT) {
                if (it.eventType == XmlPullParser.START_TAG) {
                    when (it.name) {
                        "include" -> includeCount += 1
                        "exclude" -> excludes +=
                            it.getAttributeValue(null, "domain") to it.getAttributeValue(null, "path")
                    }
                }
                it.next()
            }
        }

        assertEquals(0, includeCount)
        assertEquals(backupDomains.map { it to "." }.toSet(), excludes)
    }

    @Test
    fun installedFileProviderUsesNarrowPackagedRootsAndRejectsPrivateSentinels() {
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
        assertNotNull("FileProvider must be present in the installed manifest", provider)
        requireNotNull(provider)
        assertFalse("FileProvider must not be exported", provider.exported)
        assertTrue("FileProvider must require temporary URI grants", provider.grantUriPermissions)
        assertEquals(
            R.xml.file_paths,
            provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"),
        )

        val allowed = listOf(
            File(context.filesDir, "upload/allowed.txt"),
            File(context.cacheDir, "camera/allowed.jpg"),
            File(context.cacheDir, "temp/allowed.txt"),
            File(context.cacheDir, "export/allowed.txt"),
            File(context.cacheDir, "workspace_share/allowed.txt"),
        )
        val disallowed = mutableListOf(
            File(context.filesDir, "tool_outputs/secret.txt"),
            File(context.filesDir, "skills/secret.txt"),
            File(context.filesDir, "private-settings-sentinel.txt"),
            File(context.cacheDir, "diagnostic/secret.txt"),
        )
        context.getExternalFilesDir(null)?.let { disallowed += File(it, "private-sentinel.txt") }

        try {
            allowed.forEach { file ->
                file.parentFile?.mkdirs()
                file.writeText("allowed")
                val uri = FileProvider.getUriForFile(context, authority, file)
                assertEquals("content", uri.scheme)
                assertEquals(authority, uri.authority)
            }

            disallowed.forEach { file ->
                file.parentFile?.mkdirs()
                file.writeText("secret")
                assertThrows(
                    "Private sentinel must remain outside every packaged FileProvider root: ${file.path}",
                    IllegalArgumentException::class.java,
                ) {
                    FileProvider.getUriForFile(context, authority, file)
                }
            }
        } finally {
            (allowed + disallowed).forEach { it.delete() }
        }
    }

    private fun sectionExcludes(resourceId: Int): Map<String, Set<Pair<String, String>>> {
        val parser = context.resources.getXml(resourceId)
        val sections = linkedMapOf<String, MutableSet<Pair<String, String>>>()
        var section: String? = null
        var includeCount = 0
        parser.use {
            while (it.eventType != XmlPullParser.END_DOCUMENT) {
                when (it.eventType) {
                    XmlPullParser.START_TAG -> when (it.name) {
                        "cloud-backup", "device-transfer" -> {
                            section = it.name
                            sections.getOrPut(it.name) { linkedSetOf() }
                        }
                        "include" -> includeCount += 1
                        "exclude" -> section?.let { current ->
                            sections.getValue(current) +=
                                it.getAttributeValue(null, "domain") to it.getAttributeValue(null, "path")
                        }
                    }
                    XmlPullParser.END_TAG -> if (it.name == section) section = null
                }
                it.next()
            }
        }
        assertEquals("Fail-closed backup policy must not contain include rules", 0, includeCount)
        return sections.mapValues { it.value.toSet() }
    }
}
