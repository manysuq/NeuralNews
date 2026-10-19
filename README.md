# NeuralNews (Нейроновости)

<p align="center">
  <a href="https://github.com/manysuq/NeuralNews/releases/latest"><img alt="Download Latest Release" src="https://img.shields.io/badge/Release-v1.0.0-2ea44f?style=for-the-badge&logo=android"></a>
  <img alt="License" src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge">
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Android-green?style=for-the-badge">
  <img alt="LLM Engine" src="https://img.shields.io/badge/LLM-llama.cpp%20JNI-orange?style=for-the-badge">
</p>

**NeuralNews** — автономное Android-приложение для генерации сатирических, абсурдных и актуальных новостных заголовков с помощью локальной нейросети (**on-device LLM**).

Всё работает полностью **офлайн на вашем устройстве**: никаких API-ключей, облачных серверов или интернета. Инференс выполняется движком **llama.cpp** через прямой JNI-мост на C++.

---

## Возможности

- 🚀 **100% On-Device инференс** — встроенная дообученная модель `Qwen3.5-0.8B-News` (квантованная в `Q4_K_M` ~500 МБ) или импорт любого своего `.gguf`-файла.
- ⚡ **Стриминг токенов в реальном времени** с отображением скорости генерации (токен/сек) и затраченного времени.
- 🎲 **Гибкая генерация**:
  - Случайные темы или генерация по заданному списку ключевых слов.
  - Настройка `Temperature`, `Top-P`, `Repeat Penalty` и максимального числа токенов.
- 📋 **История и шаринг** — мгновенное копирование в буфер обмена и экспорт через системное меню «Поделиться».
- 🎨 **Material 3 UI** на Jetpack Compose с поддержкой темной темы.

---

## Архитектура проекта

```
android/
├── app/
│   ├── src/main/
│   │   ├── cpp/               # JNI-обёртка над llama.cpp (llama_jni.cpp)
│   │   ├── jniLibs/           # Скомпилированные библиотеки libllama_jni.so (arm64-v8a, armeabi-v7a, x86_64)
│   │   ├── assets/            # Встроенная gguf-модель (news-model.gguf)
│   │   └── java/com/mnsq/neuronews/
│   │       ├── MainActivity.kt
│   │       ├── MainViewModel.kt
│   │       ├── NewsScreen.kt
│   │       ├── llm/           # JNI биндинги и управление инференсом
│   │       └── data/          # Репозиторий моделей, историй и настроек
├── training/                  # Скрипты подготовки данных и LoRA-обучения Qwen
│   ├── build_dataset.py
│   ├── train.py
│   └── generate.py
├── build-android.sh           # Скрипт кросс-компиляции llama.cpp через Android NDK
└── build.gradle.kts
```

---

## Скачать (Установка)

Готовые установочные APK доступны в разделе [**Releases**](https://github.com/manysuq/NeuralNews/releases/latest):

- **`NeuralNews-arm64.apk`** — оптимизированная сборка для большинства современных смартфонов (ARM64-v8a). Включает встроенную модель.
- **`NeuralNews-universal.apk`** — универсальный APK со всеми нативными библиотеками (arm64, armv7, x86_64).

---

## Сборка из исходников

### Требования
- JDK 17 или 21
- Android SDK (API 35) + NDK 27+
- CMake 3.22+

### Сборка нативного моста (опционально, в репозитории уже есть prebuilt бинарники)
```bash
./build-android.sh arm64-v8a
```

### Сборка APK
1. Поместите квантованную GGUF-модель в `app/src/main/assets/news-model.gguf` (или скачайте из релизов).
2. Выполните сборку:
```bash
./gradlew :app:assembleRelease
```
Собранные APK будут доступны в `app/build/outputs/apk/release/`.

---

## Обучение модели (`training/`)

Модель базируется на `Qwen/Qwen3.5-0.8B`, дообученной методом LoRA на корпусе новостных заголовков:
1. `build_dataset.py` — парсинг и фильтрация новостных источников.
2. `train.py` — LoRA fine-tuning с объединением весов.
3. Экспорт в GGUF формат с квантованием `Q4_K_M` для запуска через `llama.cpp`.

---

## Лицензия

MIT License — см. [LICENSE](LICENSE).
