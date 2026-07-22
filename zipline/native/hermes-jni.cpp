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
#include <jni.h>
#include <optional>
#include <new>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "ContextJni.h"
#include "JniUtf8.h"
#include "ExceptionThrowers.h"
#include "InboundCallChannel.h"

// Android log macros - available to all functions in this file
#ifdef __ANDROID__
#define JSI_LOG_DEBUG(tag, ...) __android_log_print(ANDROID_LOG_DEBUG, tag, __VA_ARGS__)
#define JSI_LOG_INFO(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#define JSI_LOG_WARN(tag, ...) __android_log_print(ANDROID_LOG_WARN, tag, __VA_ARGS__)
#define JSI_LOG_ERROR(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
#else
#define JSI_LOG_DEBUG(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#define JSI_LOG_INFO(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#define JSI_LOG_WARN(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#define JSI_LOG_ERROR(tag, ...) fprintf(stderr, "[JSI] " __VA_ARGS__); fflush(stderr)
#endif

namespace {

inline ContextJni* toContext(jlong p) {
  return reinterpret_cast<ContextJni*>(p);
}

inline std::string jstringToCppString(JNIEnv* env, jstring javaString) {
  return zipline::jniStringToUtf8(env, javaString);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_cash_zipline_JsEngine_createContext(JNIEnv* env, jclass /*clazz*/) {
  ContextJni* c = new (std::nothrow) ContextJni(env);
  if (!c) {
    throwJavaException(env, "java/lang/OutOfMemoryError",
                       "Cannot allocate Hermes Context");
    return 0L;
  }
  return reinterpret_cast<jlong>(c);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_destroyContext(JNIEnv* /*env*/, jobject /*thiz*/,
                                            jlong context) {
  delete toContext(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_cash_zipline_JsEngine_getInboundCallChannel(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring name) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return 0L;
  }
  return reinterpret_cast<jlong>(ctx->getInboundCallChannel(env, name));
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setOutboundCallChannel(JNIEnv* env, jobject /*thiz*/,
                                                    jlong _context, jstring name,
                                                    jobject callChannel) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->setOutboundCallChannel(env, name, callChannel);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_initRdmaChangesChannel(JNIEnv* env, jobject /*thiz*/,
                                                     jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->initRdmaChangesChannel(env);
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_cash_zipline_JsEngine_execute(JNIEnv* env, jobject /*thiz*/,
                                    jlong _context, jbyteArray bytecode,
                                    jstring fileName) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  return ctx->execute(env, bytecode, fileName);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_cash_zipline_JsEngine_compile(JNIEnv* env, jobject /*thiz*/,
                                     jlong _context, jstring source,
                                     jstring filename, jstring sourceMap) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  return ctx->compile(env, source, filename, sourceMap);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setInterruptHandler(JNIEnv* env, jobject /*thiz*/,
                                                 jlong _context, jobject handler) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->setInterruptHandler(env, handler);
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_cash_zipline_JsEngine_memoryUsage(JNIEnv* env, jobject /*thiz*/,
                                        jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  return ctx->memoryUsage(env);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setMemoryLimit(JNIEnv* env, jobject /*thiz*/,
                                            jlong _context, jlong limit) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->setMemoryLimit(env, limit);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setGcThreshold(JNIEnv* env, jobject /*thiz*/,
                                            jlong _context, jlong threshold) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->setGcThreshold(env, threshold);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_gc(JNIEnv* env, jobject /*thiz*/, jlong _context) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->gc(env);
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setMaxStackSize(JNIEnv* env, jobject /*thiz*/,
                                             jlong _context, jlong stackSize) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return;
  }
  ctx->setMaxStackSize(env, stackSize);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_cash_zipline_JniCallChannel_call(JNIEnv* env, jobject /*thiz*/,
                                          jlong _context, jlong instance,
                                          jstring callJson) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "JsEngine instance was closed");
    return nullptr;
  }
  auto* channel = reinterpret_cast<const InboundCallChannel*>(instance);
  if (!channel) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "Invalid JavaScript object");
    return nullptr;
  }
  std::string result = channel->call(ctx, jstringToCppString(env, callJson));
  return zipline::utf8ToJniString(env, result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_cash_zipline_JniCallChannel_disconnect(JNIEnv* env, jobject /*thiz*/,
                                                jlong _context, jlong instance,
                                                jstring instanceName) {
  ContextJni* ctx = toContext(_context);
  if (!ctx) {
    throwJavaException(env, "java/lang/IllegalStateException",
                        "JsEngine instance was closed");
    return JNI_FALSE;
  }
  auto* channel = reinterpret_cast<const InboundCallChannel*>(instance);
  if (!channel) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "Invalid JavaScript object");
    return JNI_FALSE;
  }
  return channel->disconnect(ctx, jstringToCppString(env, instanceName)) ? JNI_TRUE : JNI_FALSE;
}

namespace {
// Helper to get a C++ string from jstring
std::string toCppString(JNIEnv* env, jstring javaString) {
  return zipline::jniStringToUtf8(env, javaString);
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_cash_zipline_JsEngine_getGlobalProperty(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring name) {
  ContextJni* ctx = reinterpret_cast<ContextJni*>(_context);
  if (!ctx || !ctx->runtime) {
    throwJavaException(env, "java/lang/IllegalStateException", "Hermes not initialized");
    return nullptr;
  }
  jsi::Runtime& rt = *ctx->runtime;
  std::string propName = toCppString(env, name);
  jsi::Value val = rt.global().getProperty(rt, propName.c_str());
  if (val.isString()) {
    return zipline::utf8ToJniString(env, val.asString(rt).utf8(rt));
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_setGlobalProperty(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring name,
                                                  jstring value) {
  ContextJni* ctx = reinterpret_cast<ContextJni*>(_context);
  if (!ctx || !ctx->runtime) {
    return;
  }
  jsi::Runtime& rt = *ctx->runtime;
  std::string propName = toCppString(env, name);
  std::string propValue = toCppString(env, value);
  rt.global().setProperty(rt, propName.c_str(),
    jsi::String::createFromUtf8(rt, propValue));
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_deleteGlobalProperty(JNIEnv* env, jobject /*thiz*/,
                                                     jlong _context, jstring name) {
  ContextJni* ctx = reinterpret_cast<ContextJni*>(_context);
  if (!ctx || !ctx->runtime) {
    return;
  }
  jsi::Runtime& rt = *ctx->runtime;
  std::string propName = toCppString(env, name);
  rt.global().setProperty(rt, propName.c_str(), jsi::Value::undefined());
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_callGlobalMethod(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context, jstring objectName,
                                                  jstring methodName) {
  ContextJni* ctx = reinterpret_cast<ContextJni*>(_context);
  if (!ctx || !ctx->runtime) {
    throwJavaException(env, "java/lang/IllegalStateException", "Hermes not initialized");
    return;
  }
  jsi::Runtime& rt = *ctx->runtime;
  std::string objName = toCppString(env, objectName);
  std::string mtdName = toCppString(env, methodName);

  // Get require(moduleId)
  jsi::Value requireVal = rt.global().getProperty(rt, "require");
  if (!requireVal.isObject() || !requireVal.asObject(rt).isFunction(rt)) {
    throwJavaException(env, "java/lang/IllegalStateException", "require not found");
    return;
  }

  // Call require(moduleId)
  jsi::Value exports = requireVal.asObject(rt).asFunction(rt).call(
    rt, jsi::String::createFromUtf8(rt, objName), 1);

  if (!exports.isObject()) {
    throwJavaException(env, "java/lang/IllegalStateException", "module exports not an object");
    return;
  }

  // Get method on exports
  jsi::Object exportsObj = exports.asObject(rt);
  jsi::Value method = exportsObj.getProperty(rt, mtdName.c_str());
  if (!method.isObject() || !method.asObject(rt).isFunction(rt)) {
    throwJavaException(env, "java/lang/IllegalStateException", "method not found");
    return;
  }

  // Call method
  method.asObject(rt).asFunction(rt).call(rt, nullptr, 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_cash_zipline_JsEngine_callGlobalFunctionWithStringArg(JNIEnv* env, jobject /*thiz*/,
                                                                  jlong _context, jstring functionName,
                                                                  jstring arg) {
  ContextJni* ctx = reinterpret_cast<ContextJni*>(_context);
  if (!ctx || !ctx->runtime) {
    throwJavaException(env, "java/lang/IllegalStateException", "Hermes not initialized");
    return nullptr;
  }
  jsi::Runtime& rt = *ctx->runtime;
  std::string fnName = toCppString(env, functionName);
  std::string argStr = toCppString(env, arg);

  jsi::Value fnVal = rt.global().getProperty(rt, fnName.c_str());
  if (!fnVal.isObject() || !fnVal.asObject(rt).isFunction(rt)) {
    throwJavaException(env, "java/lang/IllegalStateException", "function not found");
    return nullptr;
  }

  jsi::Value result = fnVal.asObject(rt).asFunction(rt).call(
    rt, jsi::String::createFromUtf8(rt, argStr), 1);

  if (result.isString()) {
    return zipline::utf8ToJniString(env, result.asString(rt).utf8(rt));
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_callRequireMethod(JNIEnv* env, jobject /*thiz*/,
                                                   jlong _context, jstring moduleId,
                                                   jstring methodName) {
  ContextJni* ctx = reinterpret_cast<ContextJni*>(_context);
  if (!ctx || !ctx->runtime) {
    throwJavaException(env, "java/lang/IllegalStateException", "Hermes not initialized");
    return;
  }
  jsi::Runtime& rt = *ctx->runtime;
  std::string modId = toCppString(env, moduleId);
  std::string mtdName = toCppString(env, methodName);

  jsi::Value requireVal = rt.global().getProperty(rt, "require");
  if (!requireVal.isObject() || !requireVal.asObject(rt).isFunction(rt)) {
    throwJavaException(env, "java/lang/IllegalStateException", "require not found");
    return;
  }

  jsi::Value exports = requireVal.asObject(rt).asFunction(rt).call(
    rt, jsi::String::createFromUtf8(rt, modId), 1);

  if (exports.isUndefined()) {
    throwJavaException(env, "java/lang/IllegalStateException", "module exports undefined");
    return;
  }

  if (!exports.isObject()) {
    throwJavaException(env, "java/lang/IllegalStateException", "module exports not an object");
    return;
  }

  // Split mtdName by '.' and traverse the object hierarchy
  // e.g., "io.clive.wb.services.wbRootMain" -> exports['io']['clive']['wb']['services']['wbRootMain']
  jsi::Value current = jsi::Value(rt, exports.asObject(rt));
  size_t start = 0;
  for (size_t i = 0; i <= mtdName.length(); i++) {
    if (i == mtdName.length() || mtdName[i] == '.') {
      std::string part = mtdName.substr(start, i - start);
      if (!current.isObject()) {
        throwJavaException(env, "java/lang/IllegalStateException", "property path traversal failed");
        return;
      }
      current = current.asObject(rt).getProperty(rt, part.c_str());
      if (i == mtdName.length()) {
        if (!current.isObject() || !current.asObject(rt).isFunction(rt)) {
          throwJavaException(env, "java/lang/IllegalStateException", "method not found");
          return;
        }
        jsi::Function methodFn = current.asObject(rt).asFunction(rt);
        methodFn.call(rt, nullptr, 0);
        return;
      }
      start = i + 1;
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_app_cash_zipline_JsEngine_installModuleLoader(JNIEnv* env, jobject /*thiz*/,
                                                  jlong _context) {
  ContextJni* ctx = reinterpret_cast<ContextJni*>(_context);
  if (!ctx || !ctx->runtime) {
    throwJavaException(env, "java/lang/IllegalStateException", "Hermes not initialized");
    return;
  }
  jsi::Runtime& rt = *ctx->runtime;

  // idToExports storage - store as a property on global object
  jsi::Object idToExportsObj(rt);
  rt.global().setProperty(rt, "app_cash_zipline_idToExports", idToExportsObj);

  // require(id) function
  auto requireFn = jsi::Function::createFromHostFunction(
    rt,
    jsi::PropNameID::forUtf8(rt, "require"),
    1,
    [&rt](jsi::Runtime& runtime, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
      if (count < 1 || !args[0].isString()) {
        throw jsi::JSError(runtime, "require expects a string id");
      }
      std::string modId = args[0].asString(runtime).utf8(runtime);

      jsi::Object idToExports = runtime.global().getProperty(runtime, "app_cash_zipline_idToExports").asObject(runtime);
      jsi::Value exports = idToExports.getProperty(runtime, modId.c_str());

      if (exports.isUndefined()) {
        throw jsi::JSError(runtime, "\"" + modId + "\" not found");
      }
      return exports;
    });
  rt.global().setProperty(rt, "require", std::move(requireFn));

  // define() function with AMD semantics
  // globalThis.define(id?, dependencies?, factory)
  auto defineFn = jsi::Function::createFromHostFunction(
    rt,
    jsi::PropNameID::forUtf8(rt, "define"),
    0,
    [&rt](jsi::Runtime& runtime, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
      // Determine if first arg is an id (string) or deps (array) or factory
      // AMD calling conventions:
      //   define(id, deps, factory) - id is string
      //   define(deps, factory) - deps is array
      //   define(factory) - factory is function
      //   define(id, factory) - id is string, no deps

      std::string modId;
      size_t factoryIndex = count - 1;
      size_t depsIndex = SIZE_MAX;

      if (count >= 2) {
        if (args[count - 2].isObject() && args[count - 2].asObject(runtime).isArray(runtime)) {
          depsIndex = count - 2;
        } else if (args[count - 2].isString()) {
          modId = args[count - 2].asString(runtime).utf8(runtime);
        }
      }

      if (modId.empty()) {
        jsi::Value currentModId = runtime.global().getProperty(runtime, "app_cash_zipline_currentModuleId");
        if (currentModId.isString()) {
          modId = currentModId.asString(runtime).utf8(runtime);
        }
      }

      if (count == 0) {
        throw jsi::JSError(runtime, "define requires at least a factory function");
      }

      if (!args[factoryIndex].isObject() || !args[factoryIndex].asObject(runtime).isFunction(runtime)) {
        throw jsi::JSError(runtime, "define last argument must be a factory function");
      }
      jsi::Function factoryFn = args[factoryIndex].asObject(runtime).asFunction(runtime);

      // Handle dependencies if provided (depsIndex points to the array)
      // Use aligned storage for Value array since jsi::Value can't be copied
      size_t depCount = 0;
      std::vector<unsigned char> depStorage;
      jsi::Value* depArgs = nullptr;
      // The exports object created for an "exports" dependency; the factory
      // may populate it instead of returning a value.
      std::optional<jsi::Object> factoryExports;

      if (depsIndex != SIZE_MAX) {
        jsi::Array deps = args[depsIndex].asObject(runtime).asArray(runtime);
        depCount = deps.length(runtime);
        depStorage.resize(depCount * sizeof(jsi::Value));
        depArgs = reinterpret_cast<jsi::Value*>(depStorage.data());
        jsi::Object idToExports = runtime.global().getProperty(runtime, "app_cash_zipline_idToExports").asObject(runtime);

        for (size_t i = 0; i < depCount; i++) {
          jsi::Value dep = deps.getValueAtIndex(runtime, i);
          if (!dep.isString()) {
            new(&depArgs[i]) jsi::Value(jsi::Value::undefined());
            continue;
          }
          std::string depId = dep.asString(runtime).utf8(runtime);

          if (depId == "exports") {
            factoryExports.emplace(runtime);
            new(&depArgs[i]) jsi::Value(runtime, *factoryExports);
          } else if (depId == "require") {
            new(&depArgs[i]) jsi::Value(runtime.global().getProperty(runtime, "require"));
          } else {
            jsi::Value depMod = idToExports.getProperty(runtime, depId.c_str());
            if (depMod.isUndefined()) {
              throw jsi::JSError(runtime, "\"" + depId + "\" not found");
            }
            new(&depArgs[i]) jsi::Value(std::move(depMod));
          }
        }
      }

      // Call the factory function - call runtime.call directly to avoid template issues
      jsi::Value result = runtime.call(factoryFn, jsi::Value::undefined(), depArgs, depCount);

      // Destroy placement-new'd values
      for (size_t i = 0; i < depCount; i++) {
        depArgs[i].~Value();
      }

      // Store exports by module id
      if (!modId.empty()) {
        jsi::Object idToExports = runtime.global().getProperty(runtime, "app_cash_zipline_idToExports").asObject(runtime);
        if (result.isObject()) {
          idToExports.setProperty(runtime, modId.c_str(), result);
        } else if (factoryExports.has_value()) {
          // CommonJS style: the factory populated the exports object it was
          // given instead of returning a value.
          idToExports.setProperty(runtime, modId.c_str(), *factoryExports);
        } else {
          idToExports.setProperty(runtime, modId.c_str(), jsi::Object(runtime));
        }
      }

      return jsi::Value::undefined();
    });
  rt.global().setProperty(rt, "define", std::move(defineFn));

  // Set define.amd = {} on the define function itself (required for UMD detection)
  jsi::Object defineObj = rt.global().getProperty(rt, "define").asObject(rt);
  jsi::Object amdObj(rt);
  defineObj.setProperty(rt, "amd", amdObj);
}
