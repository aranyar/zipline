/*
 * Copyright (C) 2024 Square, Inc.
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
