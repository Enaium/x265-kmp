/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#include <jni.h>

#include <cstdint>

#include "x265_kmp.h"

namespace {

inline jlong asHandle(const void* pointer) {
  return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
}

inline xky_params* asParams(jlong handle) {
  return reinterpret_cast<xky_params*>(static_cast<intptr_t>(handle));
}

inline xky_encoder* asEncoder(jlong handle) {
  return reinterpret_cast<xky_encoder*>(static_cast<intptr_t>(handle));
}

void releaseArray(JNIEnv* env, jbyteArray array, jbyte* data) {
  if (array != nullptr && data != nullptr) {
    env->ReleaseByteArrayElements(array, data, JNI_ABORT);
  }
}

}  // namespace

// ============================================================================
// Version
// ============================================================================

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeVersionPacked(JNIEnv* env, jclass clazz) {
  return static_cast<jint>(xky_version_packed());
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_x265_JniX265_nativeVersionString(JNIEnv* env, jclass clazz) {
  return env->NewStringUTF(xky_version_string());
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeApiBuild(JNIEnv* env, jclass clazz) {
  return static_cast<jint>(xky_api_build());
}

// ============================================================================
// Parameters
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsCreate(JNIEnv* env, jclass clazz, jint width, jint height, jint csp) {
  return asHandle(xky_params_create(width, height, csp));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsFree(JNIEnv* env, jclass clazz, jlong handle) {
  xky_params_free(asParams(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsParse(JNIEnv* env, jclass clazz, jlong handle, jstring name, jstring value) {
  if (name == nullptr) {
    return XKY_BAD_ARGUMENT;
  }
  const char* nameChars = env->GetStringUTFChars(name, nullptr);
  const char* valueChars = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
  const int result = xky_params_parse(asParams(handle), nameChars, valueChars);
  if (valueChars != nullptr) {
    env->ReleaseStringUTFChars(value, valueChars);
  }
  env->ReleaseStringUTFChars(name, nameChars);
  return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsPreset(JNIEnv* env, jclass clazz, jlong handle, jstring preset) {
  const char* chars = env->GetStringUTFChars(preset, nullptr);
  const int result = xky_params_apply_preset(asParams(handle), chars);
  env->ReleaseStringUTFChars(preset, chars);
  return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsTune(JNIEnv* env, jclass clazz, jlong handle, jstring tune) {
  const char* chars = env->GetStringUTFChars(tune, nullptr);
  const int result = xky_params_apply_tune(asParams(handle), chars);
  env->ReleaseStringUTFChars(tune, chars);
  return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsProfile(JNIEnv* env, jclass clazz, jlong handle, jstring profile) {
  if (profile == nullptr) {
    return xky_params_apply_profile(asParams(handle), nullptr);
  }
  const char* chars = env->GetStringUTFChars(profile, nullptr);
  const int result = xky_params_apply_profile(asParams(handle), chars);
  env->ReleaseStringUTFChars(profile, chars);
  return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsValidate(JNIEnv* env, jclass clazz, jlong handle) {
  return xky_params_validate(asParams(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeParamsGetInt(JNIEnv* env, jclass clazz, jlong handle, jint field) {
  return xky_params_get_int(asParams(handle), field);
}

// ============================================================================
// Encoder
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_x265_JniX265_nativeEncoderOpen(JNIEnv* env, jclass clazz, jlong handle) {
  return asHandle(xky_encoder_open(asParams(handle)));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_x265_JniX265_nativeEncoderClose(JNIEnv* env, jclass clazz, jlong handle) {
  xky_encoder_close(asEncoder(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeEncoderParameters(JNIEnv* env, jclass clazz, jlong encoder, jlong params) {
  return xky_encoder_parameters(asEncoder(encoder), asParams(params));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeEncoderHeaders(JNIEnv* env, jclass clazz, jlong handle) {
  return xky_encoder_headers(asEncoder(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeEncode(JNIEnv* env,
                                         jclass clazz,
                                         jlong handle,
                                         jbyteArray y,
                                         jbyteArray u,
                                         jbyteArray v,
                                         jint width,
                                         jint height,
                                         jint csp,
                                         jint strideY,
                                         jint strideU,
                                         jint strideV,
                                         jlong pts) {
  if (y == nullptr) {
    return xky_flush(asEncoder(handle));
  }

  jbyte* yData = env->GetByteArrayElements(y, nullptr);
  jbyte* uData = u == nullptr ? nullptr : env->GetByteArrayElements(u, nullptr);
  jbyte* vData = v == nullptr ? nullptr : env->GetByteArrayElements(v, nullptr);
  const int result = xky_encode_planes(
      asEncoder(handle),
      reinterpret_cast<const uint8_t*>(yData),
      reinterpret_cast<const uint8_t*>(uData),
      reinterpret_cast<const uint8_t*>(vData),
      strideY,
      strideU,
      strideV,
      width,
      height,
      csp,
      pts);
  releaseArray(env, u, uData);
  releaseArray(env, v, vData);
  releaseArray(env, y, yData);
  return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_x265_JniX265_nativeFlush(JNIEnv* env, jclass clazz, jlong handle) {
  return xky_flush(asEncoder(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_enaium_x265_JniX265_nativeOutputBlob(JNIEnv* env, jclass clazz, jlong handle) {
  int size = 0;
  const uint8_t* blob = xky_output_blob(asEncoder(handle), &size);
  if (blob == nullptr || size <= 0) {
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(size);
  if (result == nullptr) {
    return nullptr;
  }
  env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(blob));
  return result;
}
