#ifndef ZIPLINE_CONTEXT_NATIVE_H
#define ZIPLINE_CONTEXT_NATIVE_H

#include "ContextBase.h"

#include <hermes/hermes.h>

#include <string>
#include <vector>

namespace facebook {
namespace jsi {
class Runtime;
}
}  // namespace facebook

class ContextNative : public ContextBase {
 public:
  explicit ContextNative();
  ~ContextNative() override;

  facebook::jsi::Runtime& getRuntime() override;
  facebook::jsi::String toJsString(const std::string& str) override;
  std::string toCppString(const facebook::jsi::String& str) override;
  void throwJsException(const std::string& message) override;

  void installGcFunction();

 private:
  std::unique_ptr<facebook::hermes::HermesRuntime> hermesRuntime_;
  facebook::jsi::Runtime* runtime_;
};

#endif  // ZIPLINE_CONTEXT_NATIVE_H
