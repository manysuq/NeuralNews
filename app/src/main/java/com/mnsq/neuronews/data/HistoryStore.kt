package com.mnsq.neuronews.data

import android.content.Context
import java.io.File

data class HistoryItem(val text: String, val time: Long)

/** Лента сгенерированного: хранится простым текстовым файлом. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.txt")

    fun load(): List<HistoryItem> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val idx = line.indexOf('\t')
                    if (idx <= 0) null else HistoryItem(line.substring(idx + 1), line.substring(0, idx).toLong())
                }
        }.getOrDefault(emptyList())
    }

    fun save(items: List<HistoryItem>) {
        runCatching {
            file.writeText(items.joinToString("\n") { "${it.time}\t${it.text}" })
        }
    }
}
