package com.mnsq.neuronews.data

import android.content.Context
import java.io.File

/** Ключевые слова пользователя: файл, одна строка = одно слово/фраза. */
class KeywordsStore(context: Context) {
    private val file = File(context.filesDir, "keywords.txt")

    fun load(): String = runCatching {
        if (file.isFile) file.readText() else ""
    }.getOrDefault("")

    fun save(text: String) {
        runCatching { file.writeText(text) }
    }
}
