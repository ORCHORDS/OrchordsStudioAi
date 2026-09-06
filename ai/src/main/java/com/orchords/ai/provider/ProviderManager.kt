package com.orchords.ai.provider

import android.content.Context
import com.orchords.ai.provider.providers.claude.ClaudeProvider
import com.orchords.ai.provider.providers.google.GoogleProvider
import com.orchords.ai.provider.providers.openai.OpenAIChatToolResultPolicyProvider
import com.orchords.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 */
class ProviderManager(client: OkHttpClient, context: Context) {
    private val providers = mutableMapOf<String, Provider<*>>()
    private val providerClient = client.newBuilder()
        .addInterceptor(ProviderIngressInterceptor())
        .build()

    init {
        registerProvider(
            "openai",
            OpenAIChatToolResultPolicyProvider(OpenAIProvider(providerClient, context)),
        )
        registerProvider("google", GoogleProvider(providerClient, context))
        registerProvider("claude", ClaudeProvider(providerClient, context))
    }

    /**
     *
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     *
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     *
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
        } as Provider<T>
    }
}
