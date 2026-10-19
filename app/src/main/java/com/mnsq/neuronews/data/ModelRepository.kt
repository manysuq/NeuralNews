package com.mnsq.neuronews.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Хранит gguf-модель во внутренней памяти: копирует встроенную из assets
 * (один раз) или импортирует выбранный пользователем файл.
 */
class ModelRepository(private val context: Context) {

    val bundledModel: File get() = File(context.filesDir, "news-model.gguf")
    val customModel: File get() = File(context.filesDir, "custom-model.gguf")

    /** Пользовательский файл приоритетнее встроенного. */
    fun activeModel(): File? = when {
        customModel.isFile && customModel.length() > 0 -> customModel
        bundledModel.isFile && bundledModel.length() > 0 -> bundledModel
        else -> null
    }

    fun activeModelLabel(): String = when {
        activeModel() == customModel -> "своя модель · ${customModel.length() / 1024 / 1024} МБ"
        else -> "встроенная · Qwen3.5-0.8B-News-Q4_K_M"
    }

    suspend fun copyBundledModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = bundledModel
        if (target.isFile && target.length() > 0) return@withContext true
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            val assetManager = context.assets
            val total = try {
                assetManager.openFd(ASSET_NAME).length
            } catch (_: Exception) {
                0L
            }
            assetManager.open(ASSET_NAME).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                    output.flush()
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            tmp.delete()
            e.printStackTrace()
            false
        }
    }

    suspend fun importModel(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val tmp = File(context.filesDir, "custom-model.gguf.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, 8 * 1024 * 1024) }
            } ?: return@withContext false
            if (tmp.length() < 1024 * 1024) { // мегабайта не набирает — точно не модель
                tmp.delete()
                false
            } else {
                tmp.renameTo(customModel)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun forgetCustom() {
        customModel.delete()
    }

    companion object {
        const val ASSET_NAME = "news-model.gguf"
    }
}
