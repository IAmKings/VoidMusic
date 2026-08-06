// DrumEngine — Oboe-based low-latency drum sample playback (PRD F6 / §9.5).
//
// A single output AudioStream (AAudio where available, OpenSL ES fallback) is
// driven by an AudioStreamCallback. Each trigger() pushes a Voice onto a small
// ring; the callback mixes every active Voice into the output buffer. This
// keeps the trigger→sound path allocation-free and on the audio thread, which
// is what gets us under the PRD §4.1 40 ms stream-latency target.

#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <unordered_map>
#include <vector>
#include <atomic>
#include <cmath>
#include <memory>

#define TAG "DrumEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kMaxVoices = 16;          // simultaneous active samples
constexpr int kPadCount  = 5;           // DrumPad enum size

// A currently-playing sample instance.
struct Voice {
    const std::vector<float>* sample = nullptr;
    int   pos = 0;
    float gain = 1.0f;
    std::atomic<bool> active{false};
};

// Preloaded PCM (float, mono) keyed by pad ordinal.
struct Engine {
    std::vector<float> samples[kPadCount];
    std::vector<Voice> voices;          // pool, reused
    std::atomic<float> master{1.0f};
    std::shared_ptr<oboe::AudioStream> stream;
    oboe::AudioFormat format = oboe::AudioFormat::Float;
    int channelCount = 1;
};

Engine g_engine;

// ---- Oboe callback ----
class DrumCallback : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* stream, void* audioData, int32_t numFrames) override {

        auto& eng = g_engine;
        const float master = eng.master.load();

        if (eng.format == oboe::AudioFormat::Float) {
            auto* out = static_cast<float*>(audioData);
            for (int i = 0; i < numFrames * eng.channelCount; ++i) out[i] = 0.0f;

            for (auto& v : eng.voices) {
                if (!v.active.load()) continue;
                const auto& s = *v.sample;
                const float g = v.gain * master;
                int n = std::min((int)s.size() - v.pos, numFrames);
                if (eng.channelCount == 1) {
                    for (int i = 0; i < n; ++i) out[i] += s[v.pos + i] * g;
                } else { // duplicate to stereo
                    for (int i = 0; i < n; ++i) {
                        float val = s[v.pos + i] * g;
                        out[i * 2]     += val;
                        out[i * 2 + 1] += val;
                    }
                }
                v.pos += n;
                if (v.pos >= (int)s.size()) v.active.store(false);
            }
        } else { // I16 fallback
            auto* out = static_cast<int16_t*>(audioData);
            for (int i = 0; i < numFrames * eng.channelCount; ++i) out[i] = 0;
            for (auto& v : eng.voices) {
                if (!v.active.load()) continue;
                const auto& s = *v.sample;
                const float g = v.gain * master;
                int n = std::min((int)s.size() - v.pos, numFrames);
                if (eng.channelCount == 1) {
                    for (int i = 0; i < n; ++i)
                        out[i] = (int16_t)std::clamp(out[i] + (int)(s[v.pos + i] * g * 32767), -32768, 32767);
                } else {
                    for (int i = 0; i < n; ++i) {
                        int val = (int)(s[v.pos + i] * g * 32767);
                        out[i * 2]     = (int16_t)std::clamp((int)out[i * 2]     + val, -32768, 32767);
                        out[i * 2 + 1] = (int16_t)std::clamp((int)out[i * 2 + 1] + val, -32768, 32767);
                    }
                }
                v.pos += n;
                if (v.pos >= (int)s.size()) v.active.store(false);
            }
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result result) override {
        LOGE("Audio stream error: %s", oboe::convertToText(result));
    }
};

DrumCallback g_callback;

// Find a free voice, or steal the oldest (lowest priority) one.
Voice& acquireVoice() {
    auto& vs = g_engine.voices;
    for (auto& v : vs) if (!v.active.load()) return v;
    // All busy: steal the first (oldest).
    return vs.front();
}

} // namespace

// ---- JNI entry points ----

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_electrodig_objectdrumstudio_audio_DrumEngine_nativeStart(
        JNIEnv* env, jobject /*thiz*/, jobject /*assetMgr*/, jobject samplesMap) {

    // ---- Read the pad→float[] map from Kotlin ----
    jclass mapClass = env->GetObjectClass(samplesMap);
    jmethodID entrySet = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
    jobject entries = env->CallObjectMethod(samplesMap, entrySet);
    jclass setClass = env->GetObjectClass(entries);
    jmethodID iterator = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    jobject it = env->CallObjectMethod(entries, iterator);
    jclass itClass = env->GetObjectClass(it);
    jmethodID hasNext = env->GetMethodID(itClass, "hasNext", "()Z");
    jmethodID next     = env->GetMethodID(itClass, "next", "()Ljava/lang/Object;");
    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID getKey   = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
    jmethodID getValue = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");

    while (env->CallBooleanMethod(it, hasNext) == JNI_TRUE) {
        jobject entry = env->CallObjectMethod(it, next);
        jobject keyObj = env->CallObjectMethod(entry, getKey);
        jint pad = env->CallIntMethod(keyObj, env->GetMethodID(env->GetObjectClass(keyObj), "intValue", "()I"));
        jfloatArray fa = (jfloatArray) env->CallObjectMethod(entry, getValue);
        jsize len = env->GetArrayLength(fa);
        if (pad >= 0 && pad < kPadCount) {
            std::vector<float> v(len);
            env->GetFloatArrayRegion(fa, 0, len, v.data());
            g_engine.samples[pad] = std::move(v);
        }
        env->DeleteLocalRef(entry);
        env->DeleteLocalRef(keyObj);
        env->DeleteLocalRef(fa);
    }

    // ---- Voice pool ----
    g_engine.voices.assign(kMaxVoices, Voice{});

    // ---- Open the Oboe stream (low latency) ----
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setDataCallback(&g_callback)
           ->setErrorCallback(&g_callback);

    auto result = builder.openStream(g_engine.stream);
    if (result != oboe::Result::OK) {
        LOGI("Exclusive float stream failed (%s); retrying shared/mono I16",
             oboe::convertToText(result));
        oboe::AudioStreamBuilder b2;
        b2.setDirection(oboe::Direction::Output)
          ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
          ->setSharingMode(oboe::SharingMode::Shared)
          ->setFormat(oboe::AudioFormat::I16)
          ->setChannelCount(oboe::ChannelCount::Mono)
          ->setDataCallback(&g_callback)
          ->setErrorCallback(&g_callback);
        result = b2.openStream(g_engine.stream);
        if (result != oboe::Result::OK) {
            LOGE("Could not open stream: %s", oboe::convertToText(result));
            return JNI_FALSE;
        }
        g_engine.format = oboe::AudioFormat::I16;
    }

    g_engine.format       = g_engine.stream->getFormat();
    g_engine.channelCount = g_engine.stream->getChannelCount();

    result = g_engine.stream->start();
    if (result != oboe::Result::OK) {
        LOGE("start failed: %s", oboe::convertToText(result));
        return JNI_FALSE;
    }

    double latencyMs = 0.0;
    auto lr = g_engine.stream->getLatency();
    if (lr) latencyMs = lr.value() * 1000.0 / g_engine.stream->getSampleRate();
    LOGI("DrumEngine started: %d ch, %s, est latency %.1f ms",
         g_engine.channelCount,
         g_engine.format == oboe::AudioFormat::Float ? "Float" : "I16",
         latencyMs);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_electrodig_objectdrumstudio_audio_DrumEngine_nativeTrigger(
        JNIEnv* /*env*/, jobject /*thiz*/, jint padOrdinal, jfloat velocity) {
    if (padOrdinal < 0 || padOrdinal >= kPadCount) return;
    auto& s = g_engine.samples[padOrdinal];
    if (s.empty()) return;

    Voice& v = acquireVoice();
    v.sample = &s;
    v.pos = 0;
    // Soft velocity curve so light taps are still audible.
    v.gain = 0.4f + 0.6f * velocity;
    v.active.store(true);
}

JNIEXPORT void JNICALL
Java_com_electrodig_objectdrumstudio_audio_DrumEngine_nativeSetVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat volume) {
    g_engine.master.store(volume);
}

JNIEXPORT void JNICALL
Java_com_electrodig_objectdrumstudio_audio_DrumEngine_nativeStop(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_engine.stream) {
        g_engine.stream->stop();
        g_engine.stream->close();
        g_engine.stream.reset();
    }
    for (auto& v : g_engine.voices) v.active.store(false);
    LOGI("DrumEngine stopped");
}

}  // extern "C"
