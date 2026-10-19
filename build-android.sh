#!/usr/bin/env bash
# Builds llama.cpp + the JNI bridge into jniLibs/<abi>/libllama_jni.so
# Usage: ./build-android.sh [abi ...]   (default: arm64-v8a armeabi-v7a x86_64)
set -euo pipefail

LLAMA_SRC=${LLAMA_SRC:-/home/mnsq/neural_meduza/llama.cpp}
NDK=${NDK:-/home/mnsq/Android/Sdk/ndk/27.2.12479018}
export JAVA_HOME=${JAVA_HOME:-/home/mnsq/jdk-21}

PROJ=$(cd "$(dirname "$0")" && pwd)
OUT=$PROJ/app/src/main/jniLibs
ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
  ABIS=(arm64-v8a armeabi-v7a x86_64)
fi

JNI_INCLUDES="$JAVA_HOME/include;$JAVA_HOME/include/linux"

for ABI in "${ABIS[@]}"; do
  echo "== building $ABI =="
  BUILD=$LLAMA_SRC/build-android/$ABI
  cmake -B "$BUILD" -S "$PROJ/app/src/main/cpp" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_SRC="$LLAMA_SRC" \
    -DJNI_INCLUDES="$JNI_INCLUDES"
  cmake --build "$BUILD" --target llama_jni -j"$(nproc)"
  mkdir -p "$OUT/$ABI"
  cp "$BUILD/libllama_jni.so" "$OUT/$ABI/"
  ls -la "$OUT/$ABI/libllama_jni.so"
done
echo "ALL DONE"
