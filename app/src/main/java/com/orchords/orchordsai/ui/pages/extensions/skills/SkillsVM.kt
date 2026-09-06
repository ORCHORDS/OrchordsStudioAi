package com.orchords.orchordsai.ui.pages.extensions.skills

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orchords.orchordsai.data.files.FileUtils
import com.orchords.orchordsai.data.files.GitHubSkillImporter
import com.orchords.orchordsai.data.files.PreparedSkillPackage
import com.orchords.orchordsai.data.files.SkillImportReader
import com.orchords.orchordsai.data.files.SkillManager
import com.orchords.orchordsai.data.files.SkillMetadata
import com.orchords.orchordsai.data.files.createSkillInstallService
import com.orchords.orchordsai.data.files.installPreparedSkills
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkillsVM(
    private val skillManager: SkillManager,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init { loadSkills() }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) { _skills.value = skillManager.listSkills() }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) { onResult(result != null) }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            _skills.value = skillManager.listSkills()
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun importSkillFromFile(context: Context, uri: Uri, onResult: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        runImport(source = "local:selected-file", onResult = onResult) { checkpoint ->
            checkpoint()
            val fileName = FileUtils.getFileNameFromUri(appContext, uri).orEmpty()
            val input = appContext.contentResolver.openInputStream(uri) ?: error("Cannot read the selected skill file")
            SkillImportReader.readLocal(input, fileName, appContext.cacheDir, checkpoint = checkpoint)
        }
    }

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        runImport(source = repoUrl, onResult = onResult) { checkpoint ->
            listOf(GitHubSkillImporter().acquire(repoUrl, checkpoint))
        }
    }

    private fun runImport(
        source: String,
        onResult: (Boolean, String) -> Unit,
        prepare: (() -> Unit) -> List<PreparedSkillPackage>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = coroutineContext
            val checkpoint = { context.ensureActive() }
            try {
                val prepared = prepare(checkpoint)
                checkpoint()
                val installer = skillManager.createSkillInstallService()
                val outcome = installPreparedSkills(prepared) { skill ->
                    checkpoint()
                    val proposal = installer.propose(source, skill)
                    installer.install(proposal).installed
                }
                _skills.value = skillManager.listSkills()
                val success = outcome.failed == null
                val message = if (success) outcome.installed.joinToString() else {
                    val completed = outcome.installed.joinToString().ifEmpty { "none" }
                    "Installed: $completed. Could not confirm installation of: ${outcome.failed}. Refresh the list before retrying."
                }
                withContext(Dispatchers.Main) { onResult(success, message) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, error.message?.take(240) ?: "Skill import failed; check the source and package limits")
                }
            } finally {
                // A cancellation or IO failure never hides items that were actually published.
                _skills.value = skillManager.listSkills()
            }
        }
    }
}
