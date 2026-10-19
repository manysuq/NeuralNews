package com.mnsq.neuronews.data

import android.content.Context
import java.io.File

/** Простые настройки генерации в текстовом файле. */
class SettingsStore(context: Context) {
    private val file = File(context.filesDir, "settings.txt")

    fun loadMaxTokens(default: Int = 200): Int = runCatching {
        if (!file.isFile) return default
        file.readLines()
            .firstOrNull { it.startsWith("maxTokens=") }
            ?.substringAfter('=')
            ?.trim()
            ?.toIntOrNull()
            ?: default
    }.getOrDefault(default)

    fun saveMaxTokens(n: Int) {
        runCatching { file.writeText("maxTokens=$n\n") }
    }
}
