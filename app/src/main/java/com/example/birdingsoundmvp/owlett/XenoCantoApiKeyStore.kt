package com.example.birdingsoundmvp.owlett

import android.content.Context

class XenoCantoApiKeyStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
    keyAlias: String = KEY_ALIAS
) {
    private val delegate = OwlettApiKeyStore(
        context = context,
        preferencesName = preferencesName,
        keyAlias = keyAlias
    )

    fun save(apiKey: String) = delegate.save(apiKey)
    fun load(): String? = delegate.load()
    fun masked(): String = delegate.masked()
    fun clear() = delegate.clear()

    companion object {
        const val PREFERENCES_NAME = "owlett_xeno_canto_secure_settings"
        const val KEY_ALIAS = "owlett_xeno_canto_api_key_v1"
    }
}
