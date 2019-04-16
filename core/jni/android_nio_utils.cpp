/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "android_nio_utils.h"

#include "core_jni_helpers.h"

namespace {

void* getPointer(JNIEnv *_env, jobject buffer, jarray *array, void** elements) {
    assert(array);
    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer = jniGetNioBufferFields(_env, buffer, &position, &limit, &elementSizeShift);
    if (pointer != 0L) {
        *array = nullptr;
        *elements = nullptr;
        pointer += position << elementSizeShift;
        return reinterpret_cast<void*>(pointer);
    }
    jint offset = jniGetNioBufferBaseArrayOffset(_env, buffer);
    *array = jniGetNioBufferBaseArray(_env, buffer);
    *elements = _env->GetPrimitiveArrayCritical(*array, (jboolean *) 0);
    return reinterpret_cast<void*>(reinterpret_cast<char*>(*elements) + offset);
}

void releasePointer(JNIEnv *_env, jarray array, void *elements, jboolean commit) {
    _env->ReleasePrimitiveArrayCritical(array, elements, commit ? 0 : JNI_ABORT);
}

}  // namespace

void* android::nio_getPointer(JNIEnv *_env, jobject buffer, jarray *array) {
    void* elements;
    return getPointer(_env, buffer, array, &elements);
}

void android::nio_releasePointer(JNIEnv *_env, jarray array, void *data, jboolean commit) {
    releasePointer(_env, array, data, commit);
}

///////////////////////////////////////////////////////////////////////////////

android::AutoBufferPointer::AutoBufferPointer(JNIEnv* env, jobject nioBuffer, jboolean commit) {
    fEnv = env;
    fCommit = commit;
    fPointer = getPointer(env, nioBuffer, &fArray, &fElements);
}

android::AutoBufferPointer::~AutoBufferPointer() {
    if (nullptr != fArray) {
        releasePointer(fEnv, fArray, fElements, fCommit);
    }
}
