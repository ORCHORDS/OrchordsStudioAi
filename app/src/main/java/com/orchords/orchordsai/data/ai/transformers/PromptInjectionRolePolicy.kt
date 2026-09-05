package com.orchords.orchordsai.data.ai.transformers

import com.orchords.ai.core.MessageRole
import com.orchords.orchordsai.data.model.InjectionPosition

/** Positional content is not a tool result and cannot introduce another system message. */
internal fun requireSupportedInjectionRole(position: InjectionPosition, role: MessageRole) {
    val systemPosition = when (position) {
        InjectionPosition.BEFORE_SYSTEM_PROMPT, InjectionPosition.AFTER_SYSTEM_PROMPT -> true
        InjectionPosition.TOP_OF_CHAT, InjectionPosition.BOTTOM_OF_CHAT, InjectionPosition.AT_DEPTH -> false
    }
    val supported = when (role) {
        MessageRole.USER, MessageRole.ASSISTANT -> true
        MessageRole.SYSTEM -> systemPosition
        else -> false
    }
    require(supported) {
        "Unsupported prompt injection role $role at $position. Open Extensions > Prompts and choose a supported role; standalone entries require USER or ASSISTANT."
    }
}
