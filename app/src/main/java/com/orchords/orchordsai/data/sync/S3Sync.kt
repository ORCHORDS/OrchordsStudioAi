package com.orchords.orchordsai.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.orchords.orchordsai.data.files.FileFolders
import com.orchords.orchordsai.data.files.SafeFilePaths
import com.orchords.orchordsai.data.files.SkillPaths
import com.orchords.orchordsai.data.datastore.Settings
import com.orchords.orchordsai.data.datastore.SettingsStore
import com.orchords.orchordsai.data.datastore.validateSettingsPromptContent
import com.orchords.orchordsai.data.datastore.migration.SettingsJsonMigrator
import com.orchords.orchordsai.data.sync.s3.S3Client
import com.orchords.orchordsai.data.sync.s3.S3Config
import com.orchords.orchordsai.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val databaseSnapshotService: DatabaseSnapshotService,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        val client = getS3Client(config)
        val key = "orchordsai_backups/${file.name}"

        client.putObject(
            key = key,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        file.delete()
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val objects = buildList {
            var continuationToken: String? = null
            val seenContinuationTokens = HashSet<String>()

            while (true) {
                val result = client.listObjects(
                    prefix = "orchordsai_backups/",
                    maxKeys = 1000,
                    continuationToken = continuationToken,
                ).getOrThrow()
                addAll(result.objects)

                if (!result.isTruncated) break

                val nextToken = result.nextContinuationToken
                    ?.takeIf { it.isNotBlank() }
                    ?: error("S3 backup listing was truncated without a continuation token")
                check(seenContinuationTokens.add(nextToken)) {
                    "S3 backup listing repeated a continuation token"
                }
                continuationToken = nextToken
            }
        }

        objects
            .filter { it.key.startsWith("orchordsai_backups/backup_") && it.key.endsWith(".zip") }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()
            Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")
            restoreFromBackupFile(backupFile, config)
        } finally {
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
        val backupFile = File(context.cacheDir, newBackupFileName())

        check(backupFile.createNewFile()) {
            "Failed to allocate unique S3 backup staging file"
        }

        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = json.encodeToString(settingsStore.settingsFlow.value)
                )

                if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                    val snapshot = databaseSnapshotService.createSnapshot()
                    try {
                        addFileToZip(zipOut, snapshot, DATABASE_BACKUP_ENTRY)
                    } finally {
                        snapshot.delete()
                    }
                }

                if (config.items.contains(S3Config.BackupItem.FILES)) {
                    val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                    if (uploadFolder.exists() && uploadFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                        uploadFolder.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                            }
                        }
                    } else {
                        Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                    }

                    val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                    if (skillsFolder.exists() && skillsFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                        addDirectoryToZip(
                            zipOut = zipOut,
                            rootDir = skillsFolder,
                            currentDir = skillsFolder,
                            entryPrefix = "${FileFolders.SKILLS}/"
                        )
                    } else {
                        Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                    }

                    val fontsFolder = File(context.filesDir, FileFolders.FONTS)
                    if (fontsFolder.exists() && fontsFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up fonts from ${fontsFolder.absolutePath}")
                        fontsFolder.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                addFileToZip(zipOut, file, "${FileFolders.FONTS}/${file.name}")
                            }
                        }
                    } else {
                        Log.w(TAG, "prepareBackupFile: Fonts folder does not exist or is not a directory")
                    }
                }
            }
        } catch (error: Throwable) {
            backupFile.delete()
            throw error
        }

        Log.i(TAG, "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})")
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")
        var databaseArchiveFile: File? = null

        try {
            ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                        when (zipEntry.name) {
                            "settings.json" -> {
                                val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                                try {
                                    val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                    val settings = json.decodeFromString<Settings>(migratedJson)
                                    settingsStore.update(validateSettingsPromptContent(settings))
                                    Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                                } catch (e: Exception) {
                                    Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                    throw Exception("Failed to restore settings: ${e.message}")
                                }
                            }

                            DATABASE_BACKUP_ENTRY -> {
                                if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                                    check(databaseArchiveFile == null) {
                                        "Backup contains multiple database snapshots"
                                    }
                                    databaseArchiveFile = File.createTempFile(
                                        "orchordsai-db-archive-",
                                        ".db",
                                        context.cacheDir,
                                    ).also { archiveDatabase ->
                                        FileOutputStream(archiveDatabase).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                            outputStream.fd.sync()
                                        }
                                    }
                                }
                            }

                            "orchordsai-wal", "orchordsai-shm" -> {
                                Log.i(TAG, "restoreFromBackupFile: Ignoring legacy SQLite sidecar ${zipEntry.name}")
                            }

                            else -> {
                                if (config.items.contains(S3Config.BackupItem.FILES) && zipEntry.name.startsWith("${FileFolders.UPLOAD}/")) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                    if (fileName.isNotEmpty()) {
                                        val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                        if (!uploadFolder.exists()) {
                                            uploadFolder.mkdirs()
                                            Log.i(TAG, "restoreFromBackupFile: Created upload directory")
                                        }

                                        val targetFile = SafeFilePaths.resolveInside(uploadFolder, fileName)
                                            ?: throw IllegalArgumentException("Unsafe backup entry: ${zipEntry.name}")
                                        targetFile.parentFile?.mkdirs()
                                        Log.i(TAG, "restoreFromBackupFile: Restoring file ${zipEntry.name} to ${targetFile.absolutePath}")

                                        try {
                                            FileOutputStream(targetFile).use { outputStream ->
                                                zipIn.copyTo(outputStream)
                                            }
                                            Log.i(TAG, "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "restoreFromBackupFile: Failed to restore file ${zipEntry.name}", e)
                                            throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                        }
                                    }
                                } else if (config.items.contains(S3Config.BackupItem.FILES) && zipEntry.name.startsWith("${FileFolders.SKILLS}/")) {
                                    restoreSkillEntry(zipIn, zipEntry.name)
                                } else if (config.items.contains(S3Config.BackupItem.FILES) && zipEntry.name.startsWith("${FileFolders.FONTS}/")) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.FONTS}/")
                                    if (fileName.isNotEmpty()) {
                                        val fontsFolder = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
                                        val targetFile = SafeFilePaths.resolveDirectChild(fontsFolder, fileName)
                                            ?: throw IllegalArgumentException("Unsafe backup entry: ${zipEntry.name}")
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(TAG, "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)")
                                    }
                                } else {
                                    Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                                }
                            }
                        }

                        zipIn.closeEntry()
                    }
                }
            }

            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                val archiveDatabase = databaseArchiveFile
                    ?: error("Backup does not contain a database snapshot")
                databaseSnapshotService.restoreSnapshot(archiveDatabase)
            }
        } finally {
            databaseArchiveFile?.delete()
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
