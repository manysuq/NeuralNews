package com.mnsq.neuronews

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mnsq.neuronews.data.HistoryItem
import com.mnsq.neuronews.data.HistoryStore
import com.mnsq.neuronews.data.KeywordsStore
import com.mnsq.neuronews.data.ModelRepository
import com.mnsq.neuronews.data.SettingsStore
import com.mnsq.neuronews.llm.NewsGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { BOOTING, COPYING, LOADING, READY, GENERATING, ERROR }

    data class UiState(
        val phase: Phase = Phase.BOOTING,
        val copyProgress: Float = 0f,
        val currentText: String = "",
        val streaming: Boolean = false,
        val stats: String? = null,
        val error: String? = null,
        val message: String? = null,
        val history: List<HistoryItem> = emptyList(),
        val modelLabel: String = "",
        val keywords: String = "",
        val currentTopic: String? = null,
        val batchProgress: String? = null,
        val maxTokens: Int = 200,
    )

    private val repo = ModelRepository(app)
    private val store = HistoryStore(app)
    private val keywordsStore = KeywordsStore(app)
    private val settingsStore = SettingsStore(app)

    private val _state = MutableStateFlow(
        UiState(
            history = store.load(),
            keywords = keywordsStore.load(),
            maxTokens = settingsStore.loadMaxTokens(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var genJob: Job? = null
    private var keywordsJob: Job? = null
    private var settingsJob: Job? = null
    private var lastTopic: String = ""

    fun consumeMessage() = _state.update { it.copy(message = null) }

    init {
        prepareModel()
    }

    private fun prepareModel() {
        viewModelScope.launch {
            val active = repo.activeModel()
            if (active == null) {
                _state.update { it.copy(phase = Phase.COPYING, copyProgress = 0f) }
                val ok = repo.copyBundledModel { p ->
                    _state.update { it.copy(copyProgress = p) }
                }
                if (!ok) {
                    _state.update { it.copy(phase = Phase.ERROR, error = "Не удалось распаковать модель") }
                    return@launch
                }
            }
            loadModel()
        }
    }

    private fun loadModel() {
        val path = repo.activeModel()?.absolutePath
        if (path == null) {
            _state.update { it.copy(phase = Phase.ERROR, error = "Модель не найдена") }
            return
        }
        _state.update {
            it.copy(phase = Phase.LOADING, error = null, modelLabel = repo.activeModelLabel())
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { NewsGenerator.load(path) }
            _state.update {
                if (ok) it.copy(phase = Phase.READY, stats = null)
                else it.copy(phase = Phase.ERROR, error = "Не удалось загрузить модель")
            }
        }
    }

    fun retry() {
        prepareModel()
    }

    /** Правка списка ключевых слов: сохраняем с небольшой задержкой. */
    fun onKeywordsChange(text: String) {
        _state.update { it.copy(keywords = text) }
        keywordsJob?.cancel()
        keywordsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            keywordsStore.save(text)
        }
    }

    /** Лимит токенов на одну генерацию. */
    fun setMaxTokens(value: Int) {
        val n = value.coerceIn(16, 512)
        _state.update { it.copy(maxTokens = n) }
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            settingsStore.saveMaxTokens(n)
        }
    }

    /** Непустые строки из поля ключевых слов. */
    private fun keywordsList(): List<String> =
        _state.value.keywords.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Клик по большой кнопке: случайное слово из списка или генерация с нуля. */
    fun generate() {
        val pool = keywordsList()
        generate(if (pool.isEmpty()) "" else pool.random())
    }

    /** «Ещё раз»: та же затравка, новый seed. */
    fun generateAgain() = generate(lastTopic)

    /** Подборка: по одной новости на каждое ключевое слово, по порядку. */
    fun generateAll() {
        val topics = keywordsList()
        if (topics.isEmpty()) return
        if (_state.value.phase == Phase.GENERATING) return
        if (NewsGenerator.isLoaded.not()) {
            retry()
            return
        }
        lastTopic = topics.first()
        genJob?.cancel()
        genJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = Phase.GENERATING,
                    currentText = "",
                    streaming = true,
                    stats = null,
                    error = null,
                    batchProgress = "0 / ${topics.size}",
                )
            }
            try {
                topics.forEachIndexed { i, topic ->
                    _state.update { s -> s.copy(batchProgress = "${i + 1} / ${topics.size}") }
                    runOne(topic)
                }
                _state.update { it.copy(phase = Phase.READY, streaming = false, batchProgress = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update {
                    it.copy(
                        phase = Phase.READY,
                        streaming = false,
                        batchProgress = null,
                        error = e.message ?: "Ошибка генерации",
                    )
                }
            }
        }
    }

    private fun generate(topic: String) {
        if (_state.value.phase == Phase.GENERATING) return
        if (NewsGenerator.isLoaded.not()) {
            retry()
            return
        }
        lastTopic = topic
        genJob?.cancel()
        genJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = Phase.GENERATING,
                    currentText = "",
                    streaming = true,
                    stats = null,
                    error = null,
                    batchProgress = null,
                )
            }
            try {
                runOne(topic)
                _state.update { it.copy(phase = Phase.READY, streaming = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update {
                    it.copy(
                        phase = Phase.READY,
                        streaming = false,
                        error = e.message ?: "Ошибка генерации",
                        currentText = it.currentText,
                    )
                }
            }
        }
    }

    /** Одна генерация: стримит текст, в конце кладёт новость в ленту. */
    private suspend fun runOne(topic: String) {
        var tokens = 0
        val t0 = System.nanoTime()
        val seed = topic.trim()
        // JNI отдаёт только продолжение (без затравки), поэтому показываем «затравка + хвост».
        val prefix = if (seed.isBlank()) "" else "$seed "
        val prompt = if (seed.isBlank()) " " else "* $seed "
        _state.update {
            it.copy(currentText = prefix, streaming = true, currentTopic = seed.ifBlank { null })
        }
        val result = withContext(Dispatchers.Default) {
            NewsGenerator.generate(
                prompt = prompt,
                maxTokens = _state.value.maxTokens,
                seed = (System.nanoTime() % Int.MAX_VALUE).toInt(),
            ) { piece ->
                tokens++
                _state.update { s -> s.copy(currentText = s.currentText + piece) }
                true
            }
        }
        val continuation = result.ifBlank { _state.value.currentText.removePrefix(prefix).trim() }
        val finalText = when {
            continuation.isBlank() -> ""
            prefix.isEmpty() -> continuation.trim()
            continuation.startsWith(seed, ignoreCase = true) -> continuation.trim()
            else -> (prefix + continuation).replace(Regex("\\s+"), " ").trim()
        }
        if (finalText.isBlank()) return
        val secs = (System.nanoTime() - t0) / 1e9
        val tps = if (secs > 0) tokens / secs else 0f
        val words = finalText.split(' ', '\n', '\t').count { it.isNotBlank() }
        val stats = "${words} ${plural(words, "слово", "слова", "слов")} · %.1f с · %.0f ток/с"
            .format(secs, tps)
        val history = (listOf(HistoryItem(finalText, System.currentTimeMillis())) +
                _state.value.history).take(60)
        store.save(history)
        _state.update {
            it.copy(
                currentText = finalText,
                streaming = false,
                stats = stats,
                history = history,
            )
        }
    }

    fun stop() {
        genJob?.cancel()
        _state.update {
            it.copy(phase = Phase.READY, streaming = false, batchProgress = null, currentTopic = null)
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(message = "Импорт модели…") }
            val ok = repo.importModel(uri)
            if (ok) {
                NewsGenerator.unload()
                loadModel()
            } else {
                _state.update { it.copy(message = "Не похоже на .gguf модель") }
            }
        }
    }

    fun useBundledModel() {
        viewModelScope.launch {
            repo.forgetCustom()
            NewsGenerator.unload()
            loadModel()
        }
    }

    override fun onCleared() {
        NewsGenerator.unload()
        super.onCleared()
    }

    private fun plural(n: Int, one: String, few: String, many: String): String = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
}
