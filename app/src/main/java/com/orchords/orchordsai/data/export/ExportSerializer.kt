package com.orchords.orchordsai.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.orchords.orchordsai.data.ai.transformers.requireSupportedInjectionRole
import com.orchords.orchordsai.data.model.InjectionPosition
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.MAX_LOREBOOK_SCAN_DEPTH
import com.orchords.orchordsai.data.model.PromptInjection
import com.orchords.orchordsai.utils.toLocalString
import java.time.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    fun getExportFileName(data: T): String = "${type}.json"

    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

private fun <T : PromptInjection> validatePromptInjectionForImport(injection: T): T {
    requireSupportedInjectionRole(
        position = injection.position,
        role = injection.role,
        source = "Imported prompt injection '${injection.name}'",
    )

    if (injection is PromptInjection.RegexInjection) {
        require(injection.scanDepth in 0..MAX_LOREBOOK_SCAN_DEPTH) {
            "Lorebook scan depth must be between 0 and $MAX_LOREBOOK_SCAN_DEPTH"
        }
    }
    return injection
}

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching { decodeForImport(readUri(context, uri)) }
    }

    internal fun decodeForImport(json: String): PromptInjection.ModeInjection {
        val exportData = runCatching {
            ExportSerializer.DefaultJson.decodeFromString(ExportData.serializer(), json)
        }.getOrElse {
            throw IllegalArgumentException("Unsupported format")
        }
        require(exportData.type == type) { "Unsupported format" }

        val decoded = ExportSerializer.DefaultJson
            .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
        validatePromptInjectionForImport(decoded)
        return decoded.copy(id = Uuid.random())
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            decodeForImport(
                json = readUri(context, uri),
                fileName = getUriFileName(context, uri)?.removeSuffix(".json"),
            )
        }
    }

    internal fun decodeForImport(json: String, fileName: String?): Lorebook {
        val nativeEnvelope = runCatching {
            ExportSerializer.DefaultJson.decodeFromString(ExportData.serializer(), json)
        }.getOrNull()

        if (nativeEnvelope != null) {
            require(nativeEnvelope.type == type) { "Unsupported format" }
            val decoded = ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(nativeEnvelope.data)
            decoded.entries.forEach(::validatePromptInjectionForImport)
            return decoded.copy(
                id = Uuid.random(),
                entries = decoded.entries.map { it.copy(id = Uuid.random()) },
            )
        }

        val stLorebook = runCatching {
            ExportSerializer.DefaultJson.decodeFromString(SillyTavernLorebook.serializer(), json)
        }.getOrElse {
            throw IllegalArgumentException("Unsupported format")
        }
        val imported = Lorebook(
            id = Uuid.random(),
            name = fileName ?: LocalDateTime.now().toLocalString(),
            description = "",
            enabled = true,
            entries = stLorebook.entries.values.map { entry ->
                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    name = entry.comment.orEmpty().ifEmpty { entry.key.firstOrNull().orEmpty() },
                    enabled = !entry.disable,
                    priority = entry.order,
                    position = mapSillyTavernPosition(entry.position),
                    injectDepth = entry.depth,
                    content = entry.content,
                    keywords = entry.key,
                    useRegex = false,
                    caseSensitive = entry.caseSensitive ?: false,
                    scanDepth = entry.scanDepth ?: 4,
                    constantActive = entry.constant,
                )
            },
        )
        imported.entries.forEach(::validatePromptInjectionForImport)
        return imported
    }

    private fun mapSillyTavernPosition(position: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            2 -> InjectionPosition.TOP_OF_CHAT
            3 -> InjectionPosition.TOP_OF_CHAT
            4 -> InjectionPosition.AT_DEPTH
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }
}

@Serializable
private data class SillyTavernLorebook(
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
)
