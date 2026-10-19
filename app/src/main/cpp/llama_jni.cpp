// JNI bridge: minimal llama.cpp completion engine with token streaming.
// Mirrors the twitter bot (llama-completion): plain text continuation,
// top-p / temperature / repeat-penalty sampling, stops at first newline.
#include <jni.h>

#include <android/log.h>

#include <cstdio>
#include <string>
#include <vector>

#include "llama.h"

static bool g_backend_ready = false;

static void ensure_backend() {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
}

#define LOG_TAG "llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

struct Engine {
    llama_model   * model;
    llama_context * ctx;
};

static const char * kGenCallbackClass  = "com/mnsq/neuronews/llm/LlamaNative";
static const char * kTokenMethodName   = "onToken";
static const char * kTokenMethodSig    = "(Ljava/lang/String;)Z";

JNIEXPORT jlong JNICALL
Java_com_mnsq_neuronews_llm_LlamaNative_create(
        JNIEnv * env, jobject /*thiz*/,
        jstring modelPath, jint nCtx, jint nThreads) {
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    ensure_backend();
    llama_model_params mparams = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        LOGE("failed to load model: %s", path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (uint32_t) nCtx;
    cparams.n_batch   = (uint32_t) nCtx;
    cparams.n_threads = (int32_t) nThreads;
    cparams.n_threads_batch = cparams.n_threads;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }
    LOGI("model loaded: ctx=%u threads=%d", cparams.n_ctx, cparams.n_threads);
    auto * e = new Engine{model, ctx};
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_mnsq_neuronews_llm_LlamaNative_destroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

// Returns the full continuation; streams pieces through onToken (false = abort).
JNIEXPORT jstring JNICALL
Java_com_mnsq_neuronews_llm_LlamaNative_generate(
        JNIEnv * env, jobject /*thiz*/,
        jlong handle, jstring prompt, jint maxTokens,
        jfloat temp, jfloat topP, jfloat repeatPenalty, jint penaltyLastN,
        jint seed, jobject callback) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr || e->ctx == nullptr || callback == nullptr) {
        return env->NewStringUTF("");
    }

    const char * promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptS(promptStr ? promptStr : "");
    env->ReleaseStringUTFChars(prompt, promptStr);

    const llama_vocab * vocab = llama_model_get_vocab(e->model);

    // ---- tokenize (no BOS / specials: raw continuation like llama-completion) ----
    int32_t nPrompt = -llama_tokenize(vocab, promptS.c_str(), (int32_t) promptS.size(), nullptr, 0, false, false);
    if (nPrompt <= 0) {
        LOGE("tokenize failed: %d", nPrompt);
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens((size_t) nPrompt);
    if (llama_tokenize(vocab, promptS.c_str(), (int32_t) promptS.size(), tokens.data(), (int32_t) tokens.size(), false, false) < 0) {
        LOGE("tokenize failed (2nd pass)");
        return env->NewStringUTF("");
    }

    // ---- fresh context for this generation ----
    llama_memory_clear(llama_get_memory(e->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(e->ctx, batch) != 0) {
        LOGE("llama_decode failed (prompt)");
        return env->NewStringUTF("");
    }

    // ---- sampler: penalties -> top-p -> temperature -> dist (like the bot) ----
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl,
            llama_sampler_init_penalties(llama_vocab_n_tokens(vocab),
                                         (int32_t) penaltyLastN, repeatPenalty, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, kTokenMethodName, kTokenMethodSig);
    if (cbClass) env->DeleteLocalRef(cbClass);
    if (onToken == nullptr) {
        LOGE("callback method not found");
        llama_sampler_free(smpl);
        return env->NewStringUTF("");
    }

    std::string out;
    out.reserve(512);
    char piece[256];
    bool aborted = false;

    for (int32_t i = 0; i < maxTokens; ++i) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        llama_sampler_accept(smpl, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;

        int32_t n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n < 0) continue;
        std::string p(piece, (size_t) n);

        // one headline = first non-empty line (bot behaviour)
        if (p.find('\n') != std::string::npos) {
            if (out.find_first_not_of(" \t\r\n*") != std::string::npos) {
                break; // end of the first line
            }
            // skip leading blank lines
            if (out.find_first_not_of(" \t\r\n") != std::string::npos) {
                break;
            }
            continue;
        }

        out += p;

        jstring jp = env->NewStringUTF(p.c_str());
        jboolean keepGoing = env->CallBooleanMethod(callback, onToken, jp);
        env->DeleteLocalRef(jp);
        if (env->ExceptionCheck()) { env->ExceptionClear(); aborted = true; break; }
        if (keepGoing == JNI_FALSE) { aborted = true; break; }

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, next) != 0) {
            LOGE("llama_decode failed");
            break;
        }
    }

    llama_sampler_free(smpl);

    // trim leading '*' markers and collapse whitespace (same cleanup as the bot)
    std::string clean = out;
    size_t start = clean.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) clean.clear();
    else clean = clean.substr(start);
    while (!clean.empty() && clean.front() == '*') clean.erase(clean.begin());
    std::string result;
    result.reserve(clean.size());
    bool prevSpace = false;
    for (char c : clean) {
        if (c == '\t' || c == '\r' || c == '\n' || c == ' ') {
            if (!prevSpace && !result.empty()) result += ' ';
            prevSpace = true;
        } else {
            result += c;
            prevSpace = false;
        }
    }
    while (!result.empty() && result.back() == ' ') result.pop_back();

    if (aborted) {
        return env->NewStringUTF(result.c_str());
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_mnsq_neuronews_llm_LlamaNative_modelDesc(
        JNIEnv * env, jobject /*thiz*/, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr || e->model == nullptr) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(e->model, desc, sizeof(desc));
    char buf[512];
    snprintf(buf, sizeof(buf), "%s | ctx %d | threads %d",
             desc,
             llama_model_n_ctx_train(e->model),
             llama_n_threads(e->ctx));
    return env->NewStringUTF(buf);
}

} // extern "C"
