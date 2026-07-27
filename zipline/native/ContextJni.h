/*
 * Copyright (C) 2019 Square, Inc.
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
#ifndef ZIPLINE_CONTEXT_JNI_H
#define ZIPLINE_CONTEXT_JNI_H

#include "ContextBase.h"
#include "RdmaChange.h"
#include "hermes-core.h"

#include <jni.h>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace jsi = facebook::jsi;
namespace hermes_vm = hermes::vm;

class ContextJni : public ContextBase {
 public:
  explicit ContextJni(JNIEnv* env);
  ~ContextJni() override;

  jsi::Runtime& getRuntime() override { return *runtime; }
  jsi::String toJsString(const std::string& str) override;
  std::string toCppString(const jsi::String& str) override;
  void throwJsException(const std::string& message) override;
  void throwJsError(facebook::jsi::JSError& error) override;

  // ----- JS / bytecode lifecycle.
  jobject execute(JNIEnv* env, jbyteArray byteCode, jstring fileName);
  jbyteArray compile(JNIEnv* env, jstring source, jstring file,
                     jstring sourceMap);

  // ----- Configuration.
  jobject memoryUsage(JNIEnv* env);
  void gc(JNIEnv* env);

  // ----- Bridged call channels.
  InboundCallChannel* getInboundCallChannel(JNIEnv* env, jstring name);
  void setOutboundCallChannel(JNIEnv* env, jstring name, jobject callChannel);

  // ----- Helpers used by InboundCallChannel / OutboundCallChannel.
  jstring toJavaString(JNIEnv* env, const std::string& utf8) const;
  jstring toJavaString(JNIEnv* env, const jsi::String& s) const;
  std::string toCppString(JNIEnv* env, jstring javaString) const;
  jsi::String toJsString(JNIEnv* env, jstring javaString) const;
  jobject toJavaObject(JNIEnv* env, const jsi::Value& value,
                       bool throwOnUnsupportedType = true);
  void throwJsException(JNIEnv* env, jsi::JSError& error);
  jsi::Value throwJavaExceptionFromJs(JNIEnv* env);

  // Stashed Java throwable from a host-function call. Set by
  // throwJavaExceptionFromJs (after ExceptionClear), consumed and reset
  // by throwJsException when the wrapping JS error is observed.
  jthrowable pendingJavaException;

  JNIEnv* getEnv() const;

  // Cached JNI references used by throwers and the value converter.
  JavaVM* javaVm;
  const jint jniVersion;

  // The JSI runtime, owned by `core` (shared with the hermes-core C API so
  // the duplicated engine glue can delegate to HermesCore_* functions).
  // `runtime` is a raw pointer bound to core.runtime's lifetime.
  HermesCoreContext core;
  facebook::jsi::Runtime* runtime;

  // Hermes runtime configuration snapshot (shared Zipline defaults from
  // HermesCore_makeRuntimeConfig) — memoryUsage() reports its heap sizes.
  hermes::vm::RuntimeConfig runtimeConfig;

  // ----- Cached JNI method / class refs.
  jclass booleanClass;
  jclass integerClass;
  jclass doubleClass;
  jclass objectClass;
  jclass stringClass;
  jclass memoryUsageClass;
  jstring stringUtf8;
  jclass jsExceptionClass;
  jmethodID booleanValueOf;
  jmethodID integerValueOf;
  jmethodID doubleValueOf;
  jmethodID stringGetBytes;
  jmethodID stringConstructor;
  jmethodID memoryUsageConstructor;
  jmethodID jsExceptionConstructor;

  // RDMA Changes support
  // The class holding cached JNI references for RDMA bridging.
  jclass rdmaBridgeClass;
  jmethodID rdmaBridgeCreateCreate;
  jmethodID rdmaBridgeCreateAdd;
  jmethodID rdmaBridgeCreateRemove;
  jmethodID rdmaBridgeCreateMove;
  jmethodID rdmaBridgeCreatePropertyChange;
  jmethodID rdmaBridgeCreateModifierChange;
  jmethodID rdmaBridgeCreateModifierElement;
  jmethodID rdmaBridgeJsonPrimitiveString;
  jmethodID rdmaBridgeJsonPrimitiveInt;
  jmethodID rdmaBridgeJsonPrimitiveLong;
  jmethodID rdmaBridgeJsonPrimitiveDouble;
  jmethodID rdmaBridgeJsonPrimitiveBoolean;
  jmethodID rdmaBridgeJsonNull;
  jmethodID rdmaBridgeCreateJsonArray;
  jmethodID rdmaBridgeCreateJsonObject;
  jclass arrayListClass;
  jmethodID arrayListInit;
  jmethodID arrayListInitWithCapacity;
  jmethodID arrayListAdd;
  jobject rdmaBridgeInstance;
  jmethodID rdmaBridgeSendChanges;
  jmethodID rdmaBridgeSendBatch;

  std::vector<RdmaChange> pendingChanges;

  void cacheRdmaBridgeMethods(JNIEnv* env);
  jobject jsValueToJsonElement(JNIEnv* env, const jsi::Value& val);
  jobject jsArrayToJsonElement(JNIEnv* env, const jsi::Value& val);
  jobject jsObjectToJsonElement(JNIEnv* env, const jsi::Value& val);
  void flushPendingBatch(JNIEnv* env, int toFlush);
  void finishFlushPending(JNIEnv* env);
  void initRdmaChangesChannel(JNIEnv* env);

  std::unordered_map<std::string, jclass> globalReferences;
};

#endif  // ZIPLINE_CONTEXT_JNI_H
