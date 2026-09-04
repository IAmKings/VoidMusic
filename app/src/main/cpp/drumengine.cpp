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
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#define TAG "DrumEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kMaxVoices = 16;          // simultaneous active samples
constexpr int kPadCount  = 5;           // DrumPad enum size
constexpr size_t kTriggerQueueCapacity = 64; // bounded; must be a power of two

// A currently-playing sample instance.
struct Voice {
    const std::vector<float>* sample = nullptr;
    int   pos = 0;
    float gain = 1.0f;
    // Voice state is owned exclusively by the audio callback. JNI producers
    // communicate through TriggerQueue, so this intentionally needs no atomics.
    bool active = false;
};

struct TriggerCommand {
    int padOrdinal = 0;
    float velocity = 1.0f;
};

/**
 * Bounded multi-producer / multi-consumer ring buffer after Vyukov's sequence
 * algorithm. We currently have one consumer (the Oboe callback), but both the
 * camera analyzer and sequencer can call JNI trigger concurrently. The queue
 * keeps locks and allocations out of the real-time callback.
 */
class TriggerQueue {
public:
    TriggerQueue() { reset(); }

    void reset() {
        enqueuePos.store(0, std::memory_order_relaxed);
        dequeuePos.store(0, std::memory_order_relaxed);
        for (size_t i = 0; i < kTriggerQueueCapacity; ++i) {
            cells[i].sequence.store(i, std::memory_order_relaxed);
        }
    }

    bool enqueue(const TriggerCommand& command) {
        size_t pos = enqueuePos.load(std::memory_order_relaxed);
        while (true) {
            Cell& cell = cells[pos % kTriggerQueueCapacity];
            const size_t sequence = cell.sequence.load(std::memory_order_acquire);
            const intptr_t difference = static_cast<intptr_t>(sequence) - static_cast<intptr_t>(pos);
            if (difference == 0) {
                if (enqueuePos.compare_exchange_weak(
                        pos, pos + 1, std::memory_order_relaxed, std::memory_order_relaxed)) {
                    cell.command = command;
                    cell.sequence.store(pos + 1, std::memory_order_release);
                    return true;
                }
            } else if (difference < 0) {
                return false; // Queue full: preserve the current audio callback budget.
            } else {
                pos = enqueuePos.load(std::memory_order_relaxed);
            }
        }
    }

    bool dequeue(TriggerCommand& command) {
        size_t pos = dequeuePos.load(std::memory_order_relaxed);
        while (true) {
            Cell& cell = cells[pos % kTriggerQueueCapacity];
            const size_t sequence = cell.sequence.load(std::memory_order_acquire);
            const intptr_t difference = static_cast<intptr_t>(sequence) - static_cast<intptr_t>(pos + 1);
            if (difference == 0) {
                if (dequeuePos.compare_exchange_weak(
                        pos, pos + 1, std::memory_order_relaxed, std::memory_order_relaxed)) {
                    command = cell.command;
                    cell.sequence.store(pos + kTriggerQueueCapacity, std::memory_order_release);
                    return true;
                }
            } else if (difference < 0) {
                return false;
            } else {
                pos = dequeuePos.load(std::memory_order_relaxed);
            }
        }
    }

private:
    struct Cell {
        std::atomic<size_t> sequence{0};
        TriggerCommand command;
    };

    std::array<Cell, kTriggerQueueCapacity> cells{};
    std::atomic<size_t> enqueuePos{0};
    std::atomic<size_t> dequeuePos{0};
};

// Preloaded PCM (float, mono) keyed by pad ordinal.
struct Engine {
    std::vector<float> samples[kPadCount];
    std::array<Voice, kMaxVoices> voices; // callback-owned pool, reused
    TriggerQueue triggers;
    std::atomic<uint64_t> droppedTriggers{0};
    std::atomic<float> master{1.0f};
    std::shared_ptr<oboe::AudioStream> stream;
    oboe::AudioFormat format = oboe::AudioFormat::Float;
    int channelCount = 1;
};

Engine g_engine;

Voice& acquireVoice();

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

            TriggerCommand command;
            while (eng.triggers.dequeue(command)) {
                if (command.padOrdinal < 0 || command.padOrdinal >= kPadCount) continue;
                const auto& sample = eng.samples[command.padOrdinal];
                if (sample.empty()) continue;
                Voice& voice = acquireVoice();
                voice.sample = &sample;
                voice.pos = 0;
                voice.gain = 0.4f + 0.6f * std::clamp(command.velocity, 0.0f, 1.0f);
                voice.active = true;
            }

            for (auto& v : eng.voices) {
                if (!v.active) continue;
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
                if (v.pos >= (int)s.size()) v.active = false;
            }
        } else { // I16 fallback
            auto* out = static_cast<int16_t*>(audioData);
            for (int i = 0; i < numFrames * eng.channelCount; ++i) out[i] = 0;
            TriggerCommand command;
            while (eng.triggers.dequeue(command)) {
                if (command.padOrdinal < 0 || command.padOrdinal >= kPadCount) continue;
                const auto& sample = eng.samples[command.padOrdinal];
                if (sample.empty()) continue;
                Voice& voice = acquireVoice();
                voice.sample = &sample;
                voice.pos = 0;
                voice.gain = 0.4f + 0.6f * std::clamp(command.velocity, 0.0f, 1.0f);
                voice.active = true;
            }

            for (auto& v : eng.voices) {
                if (!v.active) continue;
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
                if (v.pos >= (int)s.size()) v.active = false;
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
    for (auto& v : vs) if (!v.active) return v;
    // All busy: steal the first (oldest).
    return vs.front();
}

} // namespace

// ---- JNI entry points ----

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_electrodig_voidmusic_audio_DrumEngine_nativeStart(
        JNIEnv* env, jobject /*thiz*/, jobject /*assetMgr*/, jobject samplesMap) {

    // ---- Read the pad→float[] map from Kotlin ----
    for (auto& sample : g_engine.samples) sample.clear();
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

    // ---- Voice pool / producer queue ----
    for (auto& voice : g_engine.voices) voice = Voice{};
    g_engine.triggers.reset();
    g_engine.droppedTriggers.store(0, std::memory_order_relaxed);

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

    const auto latency = g_engine.stream->calculateLatencyMillis();
    if (latency) {
        LOGI("DrumEngine started: %d ch, %s, est latency %.1f ms",
             g_engine.channelCount,
             g_engine.format == oboe::AudioFormat::Float ? "Float" : "I16",
             latency.value());
    } else {
        LOGI("DrumEngine started: %d ch, %s, latency unavailable (%s)",
             g_engine.channelCount,
             g_engine.format == oboe::AudioFormat::Float ? "Float" : "I16",
             oboe::convertToText(latency.error()));
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_electrodig_voidmusic_audio_DrumEngine_nativeTrigger(
        JNIEnv* /*env*/, jobject /*thiz*/, jint padOrdinal, jfloat velocity) {
    if (padOrdinal < 0 || padOrdinal >= kPadCount) return;
    if (!g_engine.triggers.enqueue({padOrdinal, velocity})) {
        g_engine.droppedTriggers.fetch_add(1, std::memory_order_relaxed);
    }
}

JNIEXPORT jlong JNICALL
Java_com_electrodig_voidmusic_audio_DrumEngine_nativeDroppedTriggerCount(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(g_engine.droppedTriggers.load(std::memory_order_relaxed));
}

JNIEXPORT void JNICALL
Java_com_electrodig_voidmusic_audio_DrumEngine_nativeSetVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat volume) {
    g_engine.master.store(volume);
}

JNIEXPORT void JNICALL
Java_com_electrodig_voidmusic_audio_DrumEngine_nativeStop(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_engine.stream) {
        g_engine.stream->stop();
        g_engine.stream->close();
        g_engine.stream.reset();
    }
    for (auto& v : g_engine.voices) v.active = false;
    LOGI("DrumEngine stopped");
}

}  // extern "C"
