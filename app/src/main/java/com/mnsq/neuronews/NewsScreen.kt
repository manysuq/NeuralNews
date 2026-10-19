package com.mnsq.neuronews

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mnsq.neuronews.ui.theme.Amber
import com.mnsq.neuronews.ui.theme.Green
import com.mnsq.neuronews.ui.theme.Ink
import com.mnsq.neuronews.ui.theme.InkCard
import com.mnsq.neuronews.ui.theme.Muted
import com.mnsq.neuronews.ui.theme.Paper
import com.mnsq.neuronews.ui.theme.PaperInk
import com.mnsq.neuronews.ui.theme.Red
import com.mnsq.neuronews.ui.theme.Stroke
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun NewsScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scroll = rememberScrollState()
    val today = remember { SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date()) }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .systemBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 28.dp),
        ) {
            // ── Шапка ────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Red, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "НЕЙРОНОВОСТИ",
                            style = MaterialTheme.typography.displaySmall,
                            color = Paper,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$today · Qwen3.5 0.8B · работает оффлайн",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                StatusDot(state.phase) { vm.retry() }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Stroke))
            Box(Modifier.width(48.dp).height(3.dp).background(Red))
            Spacer(Modifier.height(18.dp))

            // ── Бумажная карточка с заголовком ───────────────────────────────
            HeadlineCard(state)
            Spacer(Modifier.height(14.dp))

            // ── Ключевые слова ──────────────────────────────────────────────
            KeywordsField(
                value = state.keywords,
                onChange = vm::onKeywordsChange,
                maxTokens = state.maxTokens,
                onMaxTokensChange = vm::setMaxTokens,
            )
            Spacer(Modifier.height(14.dp))

            // ── Кнопка / прогресс ────────────────────────────────────────────
            when (state.phase) {
                MainViewModel.Phase.BOOTING,
                MainViewModel.Phase.COPYING,
                MainViewModel.Phase.LOADING,
                -> ModelProgress(state)

                MainViewModel.Phase.ERROR -> ErrorPanel(state.error) { vm.retry() }

                MainViewModel.Phase.READY,
                MainViewModel.Phase.GENERATING,
                -> {
                    val busy = state.phase == MainViewModel.Phase.GENERATING
                    val kwCount = state.keywords.lineSequence().count { it.isNotBlank() }
                    Surface(
                        color = if (busy) Amber else Red,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (busy) vm.stop() else vm.generate()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        color = PaperInk,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "ПИШЕТ НОВОСТЬ",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Black,
                                        color = PaperInk,
                                    )
                                    state.batchProgress?.let {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = PaperInk,
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    TypingDots(PaperInk)
                                } else {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = Paper,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        if (kwCount > 0) "СЛУЧАЙНОЕ СЛОВО" else "СГЕНЕРИРОВАТЬ НОВОСТЬ",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Black,
                                        color = Paper,
                                    )
                                }
                            }
                        }
                    }

                    // Подборка: по новости на каждое ключевое слово
                    AnimatedVisibility(
                        visible = !busy && kwCount > 0,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                color = InkCard,
                                shape = RoundedCornerShape(18.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.generateAll()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.PlaylistPlay,
                                            contentDescription = null,
                                            tint = Red,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            "ПОДБОРКА ПО ВСЕМ СЛОВАМ · $kwCount",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = Paper,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Действия ─────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = state.currentText.isNotBlank() && !state.streaming,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionPill(Icons.Filled.ContentCopy, "Копировать") {
                            copyToClipboard(context, state.currentText)
                        }
                        ActionPill(Icons.Filled.Share, "Поделиться") {
                            shareText(context, state.currentText)
                        }
                        ActionPill(Icons.Filled.Refresh, "Ещё раз") { vm.generateAgain() }
                    }
                }
            }

            // ── Лента ────────────────────────────────────────────────────────
            if (state.history.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ЛЕНТА",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = Muted,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.history.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Red,
                    )
                }
                Spacer(Modifier.height(10.dp))
                val top = state.history.take(30)
                top.forEachIndexed { i, item ->
                    HistoryRow(i + 1, item) { copyToClipboard(context, item.text) }
                    if (i != top.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }

            // ── Подвал ───────────────────────────────────────────────────────
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Stroke))
            Spacer(Modifier.height(12.dp))
            Text(
                state.modelLabel.ifBlank { "модель: Qwen3.5-0.8B-News-Q4_K_M" },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "свой .gguf",
                    style = MaterialTheme.typography.labelSmall,
                    color = Red,
                    maxLines = 1,
                    modifier = Modifier.clickable { pickModel.launch(arrayOf("*/*")) },
                )
                Text(
                    "встроенная",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    maxLines = 1,
                    modifier = Modifier.clickable { vm.useBundledModel() },
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
        )
    }

    // сообщения об импорте
    androidx.compose.runtime.LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeMessage()
    }
}

@Composable
private fun StatusDot(phase: MainViewModel.Phase, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            MainViewModel.Phase.BOOTING,
            MainViewModel.Phase.COPYING,
            MainViewModel.Phase.LOADING,
            MainViewModel.Phase.GENERATING,
            -> CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))

            MainViewModel.Phase.READY -> Icon(
                Icons.Filled.CheckCircle, contentDescription = "готово", tint = Green,
                modifier = Modifier.size(20.dp),
            )

            MainViewModel.Phase.ERROR -> Icon(
                Icons.Filled.ErrorOutline, contentDescription = "ошибка", tint = Red,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun HeadlineCard(state: MainViewModel.UiState) {
    Surface(
        color = Paper,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)
                .heightIn(min = 148.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(if (state.streaming) "● ПРЯМОЙ ЭФИР" else "ЭКСТРЕННЫЙ ВЫПУСК")
                        when {
                            state.batchProgress != null -> append(" · ${state.batchProgress}")
                            state.streaming && !state.currentTopic.isNullOrBlank() ->
                                append(" · ${state.currentTopic}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = Red,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.streaming) "нейросеть печатает" else "№ " +
                            (state.history.size + 1).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PaperInk.copy(alpha = 0.45f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(PaperInk.copy(alpha = 0.12f)))
            Spacer(Modifier.height(14.dp))

            if (state.currentText.isBlank()) {
                Text(
                    "Нажмите большую красную кнопку — и дообученная модель напишет новость.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = PaperInk.copy(alpha = 0.35f),
                )
            } else {
                Text(
                    text = state.currentText + if (state.streaming) "▍" else "",
                    style = MaterialTheme.typography.headlineMedium,
                    color = PaperInk,
                )
            }

            Spacer(Modifier.weight(1f, fill = false))
            AnimatedVisibility(visible = state.stats != null, enter = fadeIn(), exit = fadeOut()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    state.stats ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = PaperInk.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun KeywordsField(
    value: String,
    onChange: (String) -> Unit,
    maxTokens: Int,
    onMaxTokensChange: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val count = value.lineSequence().count { it.isNotBlank() }
    Surface(
        color = InkCard,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "КЛЮЧЕВЫЕ СЛОВА",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = Muted,
                )
                Spacer(Modifier.weight(1f))
                if (count > 0) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = Red,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Paper),
                cursorBrush = SolidColor(Red),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 120.dp)
                    .verticalScroll(scroll),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                decorationBox = { inner ->
                    if (value.isBlank()) {
                        Text(
                            "по слову (или фразе) на строку — Enter = новое.\n" +
                                    "Пусто? Сгенерируем с нуля.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                    inner()
                },
            )

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Stroke))
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "МАКС. ТОКЕНОВ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = Muted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    maxTokens.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = Red,
                )
            }
            Slider(
                value = maxTokens.toFloat(),
                onValueChange = { onMaxTokensChange(it.roundToInt()) },
                valueRange = 16f..512f,
                steps = 61,
                colors = SliderDefaults.colors(
                    thumbColor = Red,
                    activeTrackColor = Red,
                    inactiveTrackColor = Stroke,
                ),
            )
        }
    }
}

@Composable
private fun ActionPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        color = InkCard,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Paper, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Paper)
        }
    }
}

@Composable
private fun HistoryRow(index: Int, item: com.mnsq.neuronews.data.HistoryItem, onClick: () -> Unit) {
    Surface(
        color = InkCard,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp)) {
            Text(
                "%02d".format(index),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Red,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    relTime(item.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun ModelProgress(state: MainViewModel.UiState) {
    Surface(
        color = InkCard,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (state.phase == MainViewModel.Phase.COPYING) "Распаковка модели"
                else "Загрузка нейросети в память",
                style = MaterialTheme.typography.titleMedium,
                color = Paper,
            )
            Spacer(Modifier.height(14.dp))
            if (state.phase == MainViewModel.Phase.COPYING) {
                LinearProgressIndicator(
                    progress = { state.copyProgress },
                    color = Red,
                    trackColor = Stroke,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${(state.copyProgress * 100).toInt()} %",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            } else {
                LinearProgressIndicator(
                    color = Red,
                    trackColor = Stroke,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "около 10 МБ · потом генерация идёт на процессоре",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun ErrorPanel(error: String?, onRetry: () -> Unit) {
    Surface(
        color = InkCard,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Red),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(vertical = 18.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Red)
            Spacer(Modifier.height(8.dp))
            Text(error ?: "Что-то сломалось", color = Paper, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                "ПОВТОРИТЬ",
                color = Red,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }
    }
}

@Composable
private fun TypingDots(color: Color) {
    val dots by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "dotsN",
    )
    Text(
        ".".repeat(dots.toInt()),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Black,
        color = color,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("news", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться новостью"))
}

private fun relTime(time: Long): String {
    val d = System.currentTimeMillis() - time
    return when {
        d < 60_000 -> "только что"
        d < 3_600_000 -> "${d / 60_000} мин назад"
        d < 86_400_000 -> "${d / 3_600_000} ч назад"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(time))
    }
}
