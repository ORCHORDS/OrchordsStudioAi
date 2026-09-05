package com.orchords.orchordsai.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orchords.ai.provider.ModelAbility
import com.orchords.orchordsai.data.datastore.DEFAULT_AUTO_MODEL_ID
import com.orchords.orchordsai.data.datastore.findModelById
import com.orchords.orchordsai.R
import com.orchords.orchordsai.Screen
import com.orchords.orchordsai.data.datastore.SettingsStore
import com.orchords.orchordsai.data.extensions.BuiltInLibrary
import com.orchords.orchordsai.data.extensions.BuiltInLibraryInstallResult
import com.orchords.orchordsai.data.extensions.BuiltInLibraryInstaller
import com.orchords.orchordsai.data.files.SkillManager
import com.orchords.orchordsai.ui.context.LocalNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun BuiltInLibraryPanel(modifier: Modifier = Modifier) {
    val settingsStore = koinInject<SettingsStore>()
    val skillManager = koinInject<SkillManager>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val installer = remember(settingsStore, skillManager) { BuiltInLibraryInstaller(settingsStore, skillManager) }
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val catalog = BuiltInLibrary.catalog
    val assistant = settings.assistants.find { it.id == settings.assistantId }
    val selectedModelId = assistant?.chatModelId ?: settings.chatModelId
    val model = settings.findModelById(selectedModelId)
    val selectedServers = settings.mcpServers.filter {
        it.commonOptions.enable && assistant?.mcpServers?.contains(it.id) == true
    }
    val modelStatus = when {
        settings.init -> R.string.tool_setup_initializing
        selectedModelId == DEFAULT_AUTO_MODEL_ID -> R.string.tool_setup_automatic
        model == null -> R.string.tool_setup_no_model
        ModelAbility.TOOL !in model.abilities -> R.string.tool_setup_not_marked
        else -> R.string.tool_setup_marked
    }
    var showBrowser by remember { mutableStateOf(false) }
    if (showBrowser) BuiltInLibraryBrowser(onDismiss = { showBrowser = false })
    var installing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BuiltInLibraryInstallResult?>(null) }

    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.library_install_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.library_install_inventory, catalog.modes.size, catalog.lorebooks.size, catalog.skills.size))
            Text(stringResource(R.string.library_install_selection_notice))
            TextButton(onClick = { showBrowser = true }) { Text(stringResource(R.string.library_browser_open)) }
            Button(
                enabled = !settings.init && !installing,
                onClick = {
                    if (!installing) {
                        installing = true
                        failed = false
                        result = null
                        scope.launch {
                            try {
                                result = installer.installMissing()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                installing = false
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.library_install_missing)) }
            if (installing) CircularProgressIndicator()
            result?.let { receipt ->
                Text(
                    stringResource(R.string.library_install_result, receipt.addedModes, receipt.addedLorebooks, receipt.addedSkills, receipt.preservedSkills),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (receipt.failedSkills.isNotEmpty()) {
                    Text(stringResource(R.string.library_install_partial, receipt.failedSkills.joinToString(", ")),
                        color = MaterialTheme.colorScheme.error)
                }
            }
            if (failed) Text(stringResource(R.string.library_install_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { nav.navigate(Screen.Prompts) }) { Text(stringResource(R.string.library_open_prompts)) }
            TextButton(onClick = { nav.navigate(Screen.Skills) }) { Text(stringResource(R.string.library_open_skills)) }
            TextButton(onClick = { nav.navigate(Screen.Assistant) }) { Text(stringResource(R.string.library_open_assistants)) }
            Text(stringResource(R.string.tool_setup_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(modelStatus))
            Text(stringResource(R.string.tool_setup_counts,
                selectedServers.size,
                selectedServers.sumOf { server -> server.commonOptions.tools.count { it.enable } }))
            TextButton(onClick = { nav.navigate(Screen.Setting) }) { Text(stringResource(R.string.tool_setup_settings)) }
            TextButton(onClick = { nav.navigate(Screen.SettingMcp) }) { Text(stringResource(R.string.library_open_mcp)) }
        }
    }
}
