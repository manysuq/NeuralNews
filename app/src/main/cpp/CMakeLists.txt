cmake_minimum_required(VERSION 3.22)

project(llama_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED LLAMA_SRC)
    message(FATAL_ERROR "Define -DLLAMA_SRC=/path/to/llama.cpp")
endif()
if(NOT DEFINED JNI_INCLUDES)
    message(FATAL_ERROR "Define -DJNI_INCLUDES=jni-inc1[;jni-inc2]")
endif()

# ---- llama.cpp options (must be set before add_subdirectory) -----------------
set(BUILD_SHARED_LIBS            OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS            OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS            OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP              OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                   OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS             OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                  OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE               OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI            OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

# ---- JNI bridge --------------------------------------------------------------
add_library(llama_jni SHARED
        llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${JNI_INCLUDES})

target_link_libraries(llama_jni PRIVATE
        llama
        ggml
        ggml-base
        ggml-cpu
        android
        log)
