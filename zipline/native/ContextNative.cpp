#include "ContextNative.h"

#include <hermes/Public/GCConfig.h>
#include <hermes/Public/RuntimeConfig.h>
#include <jsi/instrumentation.h>
#include <jsi/jsi.h>

namespace jsi = facebook::jsi;

ContextNative::ContextNative() {
  auto gcConfig = hermes::vm::GCConfig()
                      .rebuild()
                      .withInitHeapSize(32u << 20)
                      .withMaxHeapSize(3u << 30)
                      .withShouldRecordStats(true)
                      .build();
  auto runtimeConfig = hermes::vm::RuntimeConfig()
                          .rebuild()
                          .withEnableEval(true)
                          .withGCConfig(gcConfig)
                          .build();

  hermesRuntime_ = facebook::hermes::makeHermesRuntime(runtimeConfig);
  if (!hermesRuntime_) {
    throw std::runtime_error("makeHermesRuntime returned null");
  }
  runtime_ = hermesRuntime_.get();
}

ContextNative::~ContextNative() = default;

jsi::Runtime& ContextNative::getRuntime() {
  return *runtime_;
}

jsi::String ContextNative::toJsString(const std::string& str) {
  return jsi::String::createFromUtf8(*runtime_, str);
}

std::string ContextNative::toCppString(const jsi::String& str) {
  return str.utf8(*runtime_);
}

void ContextNative::throwJsException(const std::string& message) {
  throw jsi::JSError(*runtime_, message);
}

void ContextNative::installGcFunction() {
  jsi::Function gcFn = jsi::Function::createFromHostFunction(
      *runtime_,
      jsi::PropNameID::forUtf8(*runtime_, "gc"),
      0,
      [](jsi::Runtime& rt, const jsi::Value& /*thisVal*/, const jsi::Value*,
         size_t) -> jsi::Value {
        rt.instrumentation().collectGarbage("host_global_gc");
        return jsi::Value::undefined();
      });
  jsi::Object globalObject = runtime_->global();
  globalObject.setProperty(*runtime_, "gc", gcFn);
}
