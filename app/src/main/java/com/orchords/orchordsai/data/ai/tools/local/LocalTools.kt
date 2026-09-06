package com.orchords.orchordsai.data.ai.tools.local

import android.content.Context
import com.orchords.ai.core.Tool
import com.orchords.orchordsai.data.datastore.SettingsStore
import com.orchords.orchordsai.data.event.AppEventBus
import com.orchords.orchordsai.data.files.AgentSkillInstallCoordinator
import com.orchords.orchordsai.data.files.SkillManager
import com.orchords.orchordsai.data.files.createSkillInstallService
import com.orchords.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    private val skillInstallTools by lazy {
        val skillManager = SkillManager(context.applicationContext, settingsStore)
        buildSkillInstallTools(
            AgentSkillInstallCoordinator(skillManager.createSkillInstallService())
        )
    }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.SkillInstaller)) {
            tools.addAll(skillInstallTools)
        }
        return tools
    }
}
