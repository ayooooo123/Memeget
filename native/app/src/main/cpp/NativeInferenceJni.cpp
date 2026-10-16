#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <executorch/runtime/platform/runtime.h>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "ModelRunner.hpp"
#include "JniStrings.hpp"

#define LOG_TAG "MemegetInferenceJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void throwJavaException(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass) {
        env->ThrowNew(exClass, message ? message : "Unknown native inference error");
    }
}

static std::mutex runnersMutex;
static std::unordered_map<jlong, std::shared_ptr<memeget::ModelRunner>> runners;
static jlong nextHandle = 1;

static std::shared_ptr<memeget::ModelRunner> getRunner(jlong handle) {
    std::scoped_lock lock(runnersMutex);
    const auto found = runners.find(handle);
    return found == runners.end() ? nullptr : found->second;
}


extern "C" {

// ART must use this bridge's load hook, not the dependency's stock Java Module registration.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    executorch::runtime::runtime_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */) {
    try {
        auto runner = std::make_shared<memeget::ModelRunner>();
        std::scoped_lock lock(runnersMutex);
        const auto handle = nextHandle++;
        runners.emplace(handle, std::move(runner));
        return handle;
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeDestroy(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        std::shared_ptr<memeget::ModelRunner> runner;
        {
            std::scoped_lock lock(runnersMutex);
            const auto found = runners.find(handle);
            if (found == runners.end()) return;
            runner = std::move(found->second);
            runners.erase(found);
        }
        runner->cancel();
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeCancel(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        auto runner = getRunner(handle);
        if (runner) runner->cancel();
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeResetCancel(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        auto runner = getRunner(handle);
        if (runner) runner->resetCancel();
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeUnload(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        auto runner = getRunner(handle);
        if (runner) runner->unloadAll();
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
    }
}

JNIEXPORT jstring JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeDiagnostics(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        auto runner = getRunner(handle);
        if (!runner) return env->NewStringUTF("runner=null");
        std::string diag = runner->getDiagnostics();
        return newJavaString(env, diag);
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeEmbedImage(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring modelPath,
    jstring imagePath,
    jint inputSize,
    jboolean normalize) {
    try {
        auto runner = getRunner(handle);
        if (!runner) {
            throwJavaException(env, "ModelRunner instance is null");
            return nullptr;
        }

        std::string mPath = JStringUtf(env, modelPath).str();
        std::string iPath = JStringUtf(env, imagePath).str();

        std::vector<float> vec = runner->embedImage(mPath, iPath, inputSize, normalize);

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(vec.size()));
        if (!result) return nullptr;
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(vec.size()), vec.data());
        return result;
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeEmbedTextTokens(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring modelPath,
    jlongArray tokenIds) {
    try {
        auto runner = getRunner(handle);
        if (!runner) {
            throwJavaException(env, "ModelRunner instance is null");
            return nullptr;
        }
        if (!tokenIds) {
            throwJavaException(env, "Token ids are null");
            return nullptr;
        }

        std::string mPath = JStringUtf(env, modelPath).str();
        const jsize count = env->GetArrayLength(tokenIds);
        std::vector<int64_t> ids(static_cast<size_t>(count));
        static_assert(sizeof(jlong) == sizeof(int64_t), "jlong must be 64-bit");
        env->GetLongArrayRegion(tokenIds, 0, count, reinterpret_cast<jlong*>(ids.data()));

        std::vector<float> vec = runner->embedTextTokens(mPath, ids);

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(vec.size()));
        if (!result) return nullptr;
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(vec.size()), vec.data());
        return result;
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeRecognize(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring detectorPath,
    jstring recognizerPath,
    jstring imagePath) {
    try {
        auto runner = getRunner(handle);
        if (!runner) {
            throwJavaException(env, "ModelRunner instance is null");
            return nullptr;
        }

        std::string dPath = JStringUtf(env, detectorPath).str();
        std::string rPath = JStringUtf(env, recognizerPath).str();
        std::string iPath = JStringUtf(env, imagePath).str();

        std::vector<memeget::OcrItem> items = runner->recognize(dPath, rPath, iPath);

        jclass itemCls = env->FindClass("com/memeget/nativeapp/inference/RawOcrDetection");
        if (!itemCls) {
            throwJavaException(env, "Class com.memeget.nativeapp.inference.RawOcrDetection not found");
            return nullptr;
        }

        jmethodID init = env->GetMethodID(itemCls, "<init>", "(Ljava/lang/String;FFFFF)V");
        if (!init) {
            throwJavaException(env, "Constructor for RawOcrDetection not found");
            return nullptr;
        }

        jobjectArray array = env->NewObjectArray(static_cast<jsize>(items.size()), itemCls, nullptr);
        for (size_t i = 0; i < items.size(); ++i) {
            jstring textStr = newJavaString(env, items[i].text);
            jobject obj = env->NewObject(
                itemCls, init, textStr,
                items[i].confidence,
                items[i].x,
                items[i].y,
                items[i].width,
                items[i].height
            );
            env->SetObjectArrayElement(array, static_cast<jsize>(i), obj);
            env->DeleteLocalRef(textStr);
            env->DeleteLocalRef(obj);
        }

        return array;
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeTranscribe(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring modelPath,
    jstring tokenizerPath,
    jfloatArray pcmSamples) {
    try {
        auto runner = getRunner(handle);
        if (!runner) {
            throwJavaException(env, "ModelRunner instance is null");
            return nullptr;
        }

        std::string mPath = JStringUtf(env, modelPath).str();
        std::string tPath = JStringUtf(env, tokenizerPath).str();

        std::vector<float> samples;
        if (pcmSamples) {
            jsize len = env->GetArrayLength(pcmSamples);
            samples.resize(len);
            env->GetFloatArrayRegion(pcmSamples, 0, len, samples.data());
        }

        std::string transcript = runner->transcribe(mPath, tPath, samples);
        return newJavaString(env, transcript);
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeSegment(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring modelPath,
    jstring imagePath,
    jint maxInstances,
    jfloat confThresh,
    jfloat iouThresh) {
    try {
        auto runner = getRunner(handle);
        if (!runner) {
            throwJavaException(env, "ModelRunner instance is null");
            return nullptr;
        }

        std::string mPath = JStringUtf(env, modelPath).str();
        std::string iPath = JStringUtf(env, imagePath).str();

        std::vector<memeget::FastSamItem> items =
            runner->segment(mPath, iPath, maxInstances, confThresh, iouThresh);

        jclass itemCls = env->FindClass("com/memeget/nativeapp/inference/RawFastSamInstance");
        if (!itemCls) {
            throwJavaException(env, "Class com.memeget.nativeapp.inference.RawFastSamInstance not found");
            return nullptr;
        }

        jmethodID init = env->GetMethodID(itemCls, "<init>", "(IIIIF[B)V");
        if (!init) {
            throwJavaException(env, "Constructor for RawFastSamInstance not found");
            return nullptr;
        }

        jobjectArray array = env->NewObjectArray(static_cast<jsize>(items.size()), itemCls, nullptr);
        for (size_t i = 0; i < items.size(); ++i) {
            const auto& item = items[i];
            jbyteArray maskArray = env->NewByteArray(static_cast<jsize>(item.mask.size()));
            env->SetByteArrayRegion(maskArray, 0, static_cast<jsize>(item.mask.size()),
                                   reinterpret_cast<const jbyte*>(item.mask.data()));

            jobject obj = env->NewObject(
                itemCls, init,
                item.x, item.y, item.width, item.height,
                item.score, maskArray
            );
            env->SetObjectArrayElement(array, static_cast<jsize>(i), obj);
            env->DeleteLocalRef(maskArray);
            env->DeleteLocalRef(obj);
        }

        return array;
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeDescribe(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring modelPath,
    jstring projectorPath,
    jstring imagePath,
    jstring systemPrompt,
    jstring userPrompt,
    jint maxTokens,
    jfloat temperature,
    jint maxImagePixels) {
    try {
        auto runner = getRunner(handle);
        if (!runner) {
            throwJavaException(env, "ModelRunner instance is null");
            return nullptr;
        }

        std::string mPath = JStringUtf(env, modelPath).str();
        std::string pPath = JStringUtf(env, projectorPath).str();
        std::string iPath = JStringUtf(env, imagePath).str();
        std::string system = JStringUtf(env, systemPrompt).str();
        std::string user = JStringUtf(env, userPrompt).str();

        std::string desc = runner->describe(mPath, pPath, iPath, system, user, maxTokens, temperature, maxImagePixels);
        return newJavaString(env, desc);
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeConvertProjector(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring sourcePath,
    jstring destinationPath) {
    try {
        auto runner = getRunner(handle);
        if (!runner) {
            throwJavaException(env, "ModelRunner instance is null");
            return 0;
        }
        return runner->convertProjector(JStringUtf(env, sourcePath).str(), JStringUtf(env, destinationPath).str());
    } catch (const std::exception& e) {
        throwJavaException(env, e.what());
        return 0;
    }
}

} // extern "C"
