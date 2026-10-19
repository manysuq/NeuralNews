package com.mnsq.neuronews.llm

/** Колбэк для стриминга токенов из нативной части. false = прекратить генерацию. */
fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

/**
 * Прямые биндинги к libllama_jni.so (обёртка над llama.cpp).
 * Логика повторяет llama-completion из твиттер-бота: обычное продолжение текста,
 * сэмплинг top-p/temperature/repeat-penalty, остановка на первой строке.
 */
internal object LlamaNative {
    init {
        System.loadLibrary("llama_jni")
    }

    external fun create(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun destroy(handle: Long)
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        repeatPenalty: Float,
        penaltyLastN: Int,
        seed: Int,
        callback: TokenCallback,
    ): String

    external fun modelDesc(handle: Long): String
}

/** Менеджер жизни модели и одна генерация на вызов. */
object NewsGenerator {
    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    fun load(path: String): Boolean {
        unload()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        handle = LlamaNative.create(path, 2048, threads)
        return handle != 0L
    }

    fun unload() {
        if (handle != 0L) {
            LlamaNative.destroy(handle)
            handle = 0L
        }
    }

    fun describe(): String = if (handle != 0L) LlamaNative.modelDesc(handle) else ""

    /**
     * @param prompt затравка, например "* В Казани "
     * @param onToken true — продолжать, false — остановить
     * @return очищенный заголовок (одна строка)
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 200,
        temp: Float = 1.15f,
        topP: Float = 0.93f,
        repeatPenalty: Float = 1.18f,
        penaltyLastN: Int = 64,
        seed: Int = (System.nanoTime() % Int.MAX_VALUE).toInt(),
        onToken: (String) -> Boolean,
    ): String {
        check(handle != 0L) { "model not loaded" }
        return LlamaNative.generate(
            handle, prompt, maxTokens, temp, topP, repeatPenalty, penaltyLastN, seed,
        ) { piece -> onToken(piece) }
    }
}
